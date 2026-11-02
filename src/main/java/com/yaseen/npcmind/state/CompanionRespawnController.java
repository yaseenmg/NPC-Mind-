package com.yaseen.npcmind.state;

import com.yaseen.npcmind.Config;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.entity.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import com.yaseen.npcmind.api.Internal;

/** Server-thread respawn lifecycle for owned companions. Respawns start with an empty inventory. */
@Internal
public final class CompanionRespawnController {
    private static long tickCounter;

    public static void tick(MinecraftServer server) {
        if (server == null || !Config.AUTO_RESPAWN.get() || ++tickCounter % 20L != 0L) return;
        var players = server.getPlayerList().getPlayers();
        for (ServerLevel dataLevel : server.getAllLevels()) {
            CompanionWorldData data = CompanionWorldData.get(dataLevel);
            for (ServerPlayer owner : players) {
                if (!data.isRespawnReady(owner.getUUID())) continue;
                if (hasLiveCompanion(server, owner.getUUID())) {
                    data.clearPending(owner.getUUID());
                    continue;
                }

                net.minecraft.resources.ResourceLocation dimension = data.dimension(owner.getUUID());
                ServerLevel targetLevel = dimension == null ? null : server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimension));
                BlockPos saved = new BlockPos((int) data.x(owner.getUUID()), (int) data.y(owner.getUUID()), (int) data.z(owner.getUUID()));
                if (targetLevel == null || !targetLevel.hasChunkAt(saved) || targetLevel != owner.serverLevel()) {
                    // Do not force-load remote/unloaded chunks. Keep the NPC with its owner instead.
                    targetLevel = owner.serverLevel();
                    saved = owner.blockPosition().offset(1, 0, 0);
                }

                CompanionEntity companion = ModEntities.COMPANION.get().create(targetLevel);
                if (companion == null) continue;
                BlockPos spawn = safeSurface(targetLevel, saved, companion);
                if (spawn == null) { companion.discard(); continue; }
                companion.moveTo(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
                        owner.getYRot(), 0.0F);
                companion.setOwner(owner.getUUID());
                // Restore the custom name instead of resetting every respawn to "Steve".
                String savedName = data.customName(owner.getUUID());
                companion.setDisplayName(savedName == null || savedName.isBlank() ? "Steve" : savedName);
                // UUID changes when the companion is recreated, so owner UUID is the stable
                // lifecycle key for learning that must survive automatic respawn.
                java.util.UUID deadId = data.getDeadCompanionId(owner.getUUID());
                companion.getSkills().load(data.getSkills(owner.getUUID(), deadId));
                // Permissions are player-chosen safety gates; losing them silently re-enabled
                // drops/destroys after every respawn and contradicted the documented persistence.
                companion.getPermissions().load(data.getPermissionSnapshot(owner.getUUID()));
                // Tool-skill statistics are keyed by the companion UUID, which changed with the
                // respawn; seed the new store from the owner-keyed death snapshot.
                com.yaseen.npcmind.core.SkillLibrary.restoreFromOwnerSnapshot(companion,
                        data.getToolSkillsSnapshot(owner.getUUID()));
                net.minecraft.nbt.CompoundTag xp = data.getExperience(owner.getUUID());
                if (!xp.isEmpty()) companion.setExperienceState(xp.getInt("level"), xp.getFloat("progress"), xp.getInt("total"));
                if (targetLevel.addFreshEntity(companion)) data.clearPending(owner.getUUID());
            }
        }
    }

    /** Prevent a manual spawn command from racing an already pending automatic respawn. */
    public static boolean hasPendingRespawn(MinecraftServer server, java.util.UUID owner) {
        if (server == null || owner == null) return false;
        for (ServerLevel level : server.getAllLevels()) {
            if (CompanionWorldData.get(level).isPending(owner)) return true;
        }
        return false;
    }

    /** Live-companion directory: owner -> (companionId, dimension). O(#companions) instead
     *  of a every-entity-of-every-dimension scan every 20 ticks per player. */
    private record OwnedRef(java.util.UUID companionId, net.minecraft.resources.ResourceLocation dimension) {}
    private static final java.util.Map<java.util.UUID, OwnedRef> LIVE_BY_OWNER = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, java.util.UUID> OWNER_OF = new java.util.concurrent.ConcurrentHashMap<>();

    /** Called from {@code CompanionEntity.setOwner} — every owner assignment is a live companion. */
    public static void registerLiveCompanion(java.util.UUID owner, java.util.UUID companionId,
                                             net.minecraft.resources.ResourceLocation dimension) {
        if (owner == null || companionId == null) return;
        LIVE_BY_OWNER.put(owner, new OwnedRef(companionId, dimension));
        OWNER_OF.put(companionId, owner);
    }

    /** Called from {@code CompanionCleanup.purge} — the companion entity is gone for good. */
    public static void unregisterLiveCompanion(java.util.UUID companionId) {
        if (companionId == null) return;
        java.util.UUID owner = OWNER_OF.remove(companionId);
        if (owner != null) {
            OwnedRef ref = LIVE_BY_OWNER.get(owner);
            if (ref != null && ref.companionId().equals(companionId)) LIVE_BY_OWNER.remove(owner);
        }
    }

    private static boolean hasLiveCompanion(MinecraftServer server, java.util.UUID owner) {
        // Directory lookup instead of the old getAllLevels × getAllEntities scan (up to
        // ~100k iterations every 20 ticks per player on busy servers).
        OwnedRef ref = LIVE_BY_OWNER.get(owner);
        if (ref == null) return false;
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.dimension().location().equals(ref.dimension())) continue;
            net.minecraft.world.entity.Entity entity = level.getEntity(ref.companionId());
            if (entity instanceof CompanionEntity companion && companion.isAlive()
                    && owner.equals(companion.getOwner())) return true;
            // Stale entry (entity unloaded or replaced): drop it so the directory cannot
            // grow or permanently block a spawn. Chunk-unload does not purge, matching the
            // old scan which also only saw loaded entities.
            unregisterLiveCompanion(ref.companionId());
            return false;
        }
        unregisterLiveCompanion(ref.companionId());
        return false;
    }

    private static BlockPos safeSurface(ServerLevel level, BlockPos requested, CompanionEntity companion) {
        java.util.List<BlockPos> columns = new java.util.ArrayList<>(49);
        for (BlockPos pos : BlockPos.betweenClosed(requested.offset(-3, 0, -3), requested.offset(3, 0, 3))) {
            columns.add(pos.immutable());
        }
        columns.sort(java.util.Comparator
                .comparingInt((BlockPos pos) -> Math.max(Math.abs(pos.getX() - requested.getX()),
                        Math.abs(pos.getZ() - requested.getZ())))
                .thenComparingDouble(pos -> pos.distSqr(requested)));

        for (BlockPos column : columns) {
            if (!level.isInWorldBounds(column) || !level.getWorldBorder().isWithinBounds(column)
                    || !level.hasChunkAt(column)) continue;
            BlockPos feet = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, column);
            BlockPos head = feet.above();
            BlockPos floor = feet.below();
            if (!level.isInWorldBounds(head) || !level.hasChunkAt(feet)
                    || !level.hasChunkAt(head) || !level.hasChunkAt(floor)) continue;
            if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    || !level.getBlockState(head).getCollisionShape(level, head).isEmpty()
                    || !level.getFluidState(feet).isEmpty() || !level.getFluidState(head).isEmpty()) continue;
            if (level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()) continue;
            double x = feet.getX() + 0.5D;
            double y = feet.getY();
            double z = feet.getZ() + 0.5D;
            net.minecraft.world.phys.AABB box = companion.getDimensions(
                    net.minecraft.world.entity.Pose.STANDING).makeBoundingBox(x, y, z);
            if (level.noCollision(companion, box)) return feet;
        }
        return null;
    }

    private CompanionRespawnController() {}
}
