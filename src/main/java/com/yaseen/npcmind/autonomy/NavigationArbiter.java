package com.yaseen.npcmind.autonomy;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.yaseen.npcmind.api.Internal;

/**
 * Single navigation lease/priority owner. Every movement controller must request a lease here.
 */
@Internal
public final class NavigationArbiter {
    public record Lease(String owner, int priority, long untilTick, BlockPos target, double speed) {}
    private static final Map<UUID, Lease> LEASES = new ConcurrentHashMap<>();
    private static final int MAX_DIRECT_HORIZONTAL_DISTANCE = 48;
    private static final int LOCAL_WAYPOINT_STEP = 12;
    /** Path computations per waypoint search. The old value of 16 ran inside every denied request
     * and FollowOwnerGoal re-requests every 10 ticks, so a single companion could burn up to 16
     * A* searches per tick. Four keeps the search responsive without the CPU spike. */
    private static final int MAX_LOCAL_PATH_CHECKS = 4;

    private NavigationArbiter() {}

    public static boolean request(CompanionEntity c, BlockPos target, String owner, int priority, double speed) {
        if (c == null || target == null || owner == null || owner.isBlank() || c.level().isClientSide()) return false;
        if (!(c.level() instanceof ServerLevel level) || !level.isInWorldBounds(target)
                || !level.getWorldBorder().isWithinBounds(target)) return false;
        long now = level.getGameTime();
        BlockPos immutableTarget = target.immutable();

        Lease old = LEASES.get(c.getUUID());
        if (old != null && old.untilTick() > now && old.priority() > priority && !old.owner().equals(owner)) return false;
        // Many controllers issue identical requests every tick. Do not reset the vanilla path while
        // it is making progress: that reset was a cause of vibration and never-ending walking.
        if (old != null && old.owner().equals(owner) && old.target().equals(immutableTarget)
                && Math.abs(old.speed() - speed) < 0.06D && !c.getNavigation().isDone()) {
            LEASES.put(c.getUUID(), new Lease(owner, Math.max(old.priority(), priority), now + 20, immutableTarget, speed));
            return true;
        }

        Path direct = createBoundedPath(c, immutableTarget, 0.35F);
        if (isUsefulPath(immutableTarget, direct)
                && c.getNavigation().moveTo(direct, speed)) {
            LEASES.put(c.getUUID(), new Lease(owner, priority, now + 20, immutableTarget, speed));
            return true;
        }

        // A target may be farther than the vanilla path window, behind an unloaded chunk, or on
        // the far side of a local obstruction. Advance by safe loaded waypoints and re-plan as the
        // NPC moves; never ask vanilla to synchronously inspect an unlimited region.
        Path waypoint = createLocalWaypointPath(c, immutableTarget);
        if (waypoint != null && c.getNavigation().moveTo(waypoint, speed)) {
            LEASES.put(c.getUUID(), new Lease(owner, priority, now + 20, immutableTarget, speed));
            return true;
        }
        return false;
    }

    /** Creates a low-cost path only when the route corridor is already loaded. */
    private static Path createBoundedPath(CompanionEntity c, BlockPos target, float nodeMultiplier) {
        if (!(c.level() instanceof ServerLevel level) || target == null || !level.hasChunkAt(target)) return null;
        int dx = target.getX() - c.getBlockX();
        int dz = target.getZ() - c.getBlockZ();
        int horizontal = (int) Math.ceil(Math.sqrt((double) dx * dx + (double) dz * dz));
        if (horizontal > MAX_DIRECT_HORIZONTAL_DISTANCE
                || !hasLoadedChunkCorridor(level, c.blockPosition(), target)) return null;

        var navigation = c.getNavigation();
        navigation.setMaxVisitedNodesMultiplier(Math.max(0.05F, Math.min(0.75F, nodeMultiplier)));
        try {
            int localRange = Math.max(8, Math.min(MAX_DIRECT_HORIZONTAL_DISTANCE, horizontal + 6));
            return navigation.createPath(target, 0, localRange);
        } catch (RuntimeException ex) {
            // A bad/vanished chunk should produce a recoverable no-path result, not a tick crash.
            return null;
        } finally {
            navigation.resetMaxVisitedNodesMultiplier();
        }
    }

    private static boolean isUsefulPath(BlockPos target, Path path) {
        if (path == null || !path.canReach()) return false;
        Node end = path.getEndNode();
        if (end == null) return false;
        BlockPos endPos = end.asBlockPos();
        return Math.abs(endPos.getY() - target.getY()) <= 2 && endPos.distSqr(target) <= 9.0D;
    }

    /** Tries at most {@value #MAX_LOCAL_PATH_CHECKS} safe, reachable local candidates per request. */
    private static Path createLocalWaypointPath(CompanionEntity c, BlockPos target) {
        if (!(c.level() instanceof ServerLevel level)) return null;
        BlockPos from = c.blockPosition();
        double dx = target.getX() - from.getX();
        double dz = target.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double step = Math.min(LOCAL_WAYPOINT_STEP, Math.max(0.0D, horizontal - 1.5D));
        double ux = horizontal < 0.001D ? 0.0D : dx / horizontal;
        double uz = horizontal < 0.001D ? 0.0D : dz / horizontal;
        int yDelta = target.getY() - from.getY();
        int yStep = Math.max(-2, Math.min(2, yDelta));
        BlockPos center = new BlockPos((int)Math.floor(from.getX() + ux * step),
                from.getY() + yStep, (int)Math.floor(from.getZ() + uz * step));

        List<BlockPos> candidates = new ArrayList<>();
        for (int radius = 0; radius <= 4 && candidates.size() < 72; radius++) {
            for (int dx2 = -radius; dx2 <= radius && candidates.size() < 72; dx2++) {
                for (int dz2 = -radius; dz2 <= radius && candidates.size() < 72; dz2++) {
                    if (radius > 0 && Math.max(Math.abs(dx2), Math.abs(dz2)) != radius) continue;
                    for (int dy = 2; dy >= -2; dy--) {
                        BlockPos pos = center.offset(dx2, dy, dz2);
                        if (!canStandAt(level, pos) || pos.distSqr(from) < 0.75D) continue;
                        // Do not choose a waypoint that heads backwards unless the route is vertical.
                        double currentDistance = from.distSqr(target);
                        double candidateDistance = pos.distSqr(target);
                        if (candidateDistance >= currentDistance - 0.5D) continue;
                        if (!candidates.contains(pos)) candidates.add(pos.immutable());
                    }
                }
            }
        }
        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> pos.distSqr(target))
                .thenComparingDouble(pos -> pos.distSqr(from)));

        int checks = 0;
        for (BlockPos candidate : candidates) {
            if (checks++ >= MAX_LOCAL_PATH_CHECKS) break;
            Path path = createBoundedPath(c, candidate, 0.2F);
            if (isUsefulPath(candidate, path)) return path;
        }
        return null;
    }

    private static boolean canStandAt(ServerLevel level, BlockPos pos) {
        if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)
                || !level.hasChunkAt(pos) || !level.hasChunkAt(pos.above()) || !level.hasChunkAt(pos.below())) return false;
        var feet = level.getBlockState(pos);
        var head = level.getBlockState(pos.above());
        var floor = level.getBlockState(pos.below());
        return feet.getCollisionShape(level, pos).isEmpty()
                && head.getCollisionShape(level, pos.above()).isEmpty()
                && feet.getFluidState().isEmpty() && head.getFluidState().isEmpty()
                && !floor.getCollisionShape(level, pos.below()).isEmpty()
                && floor.isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP);
    }

    private static boolean hasLoadedChunkCorridor(ServerLevel level, BlockPos from, BlockPos to) {
        int x = from.getX() >> 4;
        int z = from.getZ() >> 4;
        int endX = to.getX() >> 4;
        int endZ = to.getZ() >> 4;
        int deltaX = Math.abs(endX - x);
        int deltaZ = Math.abs(endZ - z);
        int directionX = Integer.compare(endX, x);
        int directionZ = Integer.compare(endZ, z);
        int error = deltaX - deltaZ;

        while (true) {
            // Navigation samples a volume around its route, so require the neighboring loaded
            // area too. The route walk is integer-grid based and stops before inspecting any
            // off-route distant region.
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                    if (!level.hasChunk(x + offsetX, z + offsetZ)) return false;
                }
            }
            if (x == endX && z == endZ) return true;

            int doubledError = error * 2;
            if (doubledError > -deltaZ) {
                error -= deltaZ;
                x += directionX;
            }
            if (doubledError < deltaX) {
                error += deltaX;
                z += directionZ;
            }
        }
    }

    public static boolean request(CompanionEntity c, Entity target, String owner, int priority, double speed) {
        if (c == null || target == null || !target.isAlive() || target.level() != c.level()) return false;
        // Entity#moveTo bypasses our loaded-corridor and bounded-search policy. Snapshot the
        // moving target's current block and let the controller throttle subsequent replans.
        return request(c, target.blockPosition(), owner, priority, speed);
    }

    public static void release(CompanionEntity c, String owner) {
        if (c == null || owner == null) return;
        Lease lease = LEASES.get(c.getUUID());
        if (lease != null && lease.owner().equals(owner)) LEASES.remove(c.getUUID(), lease);
    }

    /** Drops any lease for a companion that died or was removed. */
    public static void release(UUID id) { if (id != null) LEASES.remove(id); }
    public static String owner(CompanionEntity c) { Lease lease = c == null ? null : LEASES.get(c.getUUID()); return lease == null ? "none" : lease.owner(); }
    public static BlockPos target(CompanionEntity c) { Lease lease = c == null ? null : LEASES.get(c.getUUID()); return lease == null ? null : lease.target(); }
    public static void tick(CompanionEntity c) {
        if (c == null) return;
        Lease lease = LEASES.get(c.getUUID());
        if (lease != null && lease.untilTick() <= c.level().getGameTime()) LEASES.remove(c.getUUID(), lease);
    }
}
