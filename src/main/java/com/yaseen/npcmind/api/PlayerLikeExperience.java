package com.yaseen.npcmind.api;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.core.ServerThreadGuard;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;

/**
 * Optional player-like experience state for entities that are not ServerPlayer instances.
 *
 * <p>CompanionEntity uses synchronized entity data and its normal entity save data. For other
 * LivingEntity implementations this helper uses Forge's persistent entity NBT, so the host mod
 * does not need to add fields or a dependency on CompanionEntity. Call reads and mutations on the
 * logical server thread; reads return an empty snapshot and mutations return false off-thread.
 * The host remains responsible for deciding which actions reward XP.</p>
 */
public final class PlayerLikeExperience {
    private static final String ROOT = "NPCMindPlayerLikeExperience";
    private static final int MAX_LEVEL = 21862;

    public record Snapshot(int level, float progress, int totalPoints) {}

    private PlayerLikeExperience() {}

    public static Snapshot read(LivingEntity entity) {
        if (!ServerThreadGuard.isOnServerThread(entity)) return new Snapshot(0, 0.0F, 0);
        if (entity instanceof CompanionEntity companion) {
            return new Snapshot(companion.getExperienceLevel(), companion.getExperienceProgress(),
                    companion.getTotalExperiencePoints());
        }
        CompoundTag data = entity.getPersistentData();
        CompoundTag state = data.contains(ROOT, 10) ? data.getCompound(ROOT) : new CompoundTag();
        return normalize(state.getInt("level"), state.getFloat("progress"), state.getInt("total"));
    }

    /** Grant positive XP points and update level/progress using the vanilla 1.20.1 level curve. */
    public static boolean grant(LivingEntity entity, int points) {
        if (points <= 0 || !ServerThreadGuard.isOnServerThread(entity)) return false;
        if (entity instanceof CompanionEntity companion) {
            companion.addExperiencePoints(points);
            return true;
        }
        Snapshot old = read(entity);
        int level = old.level();
        int oldNeed = pointsToNextLevel(level);
        float withinLevel = old.progress() * oldNeed + (float) points;
        int nextNeed = oldNeed;
        while (withinLevel >= nextNeed && level < MAX_LEVEL) {
            withinLevel -= nextNeed;
            level++;
            nextNeed = pointsToNextLevel(level);
        }
        long totalLong = (long) old.totalPoints() + points;
        int total = (int) Math.min(Integer.MAX_VALUE, totalLong);
        float progress = level >= MAX_LEVEL ? 0.0F : withinLevel / Math.max(1, nextNeed);
        if (level >= MAX_LEVEL) total = Integer.MAX_VALUE;
        Snapshot next = normalize(level, progress, total);
        CompoundTag state = new CompoundTag();
        state.putInt("level", next.level());
        state.putFloat("progress", next.progress());
        state.putInt("total", next.totalPoints());
        entity.getPersistentData().put(ROOT, state);
        return true;
    }

    /** Number of raw XP points required to advance from the given level, matching vanilla. */
    public static int pointsToNextLevel(int level) {
        int l = Math.max(0, level);
        if (l >= 30) return 112 + (l - 30) * 9;
        if (l >= 15) return 37 + (l - 15) * 5;
        return 7 + l * 2;
    }

    private static Snapshot normalize(int level, float progress, int total) {
        float safeProgress = Float.isFinite(progress) ? Math.max(0.0F, Math.min(0.999999F, progress)) : 0.0F;
        return new Snapshot(Math.max(0, Math.min(MAX_LEVEL, level)), safeProgress, Math.max(0, total));
    }
}
