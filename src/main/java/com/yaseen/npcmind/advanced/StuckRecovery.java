package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.autonomy.NavigationArbiter;
import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import com.yaseen.npcmind.api.Internal;

/** Bounded stuck recovery with incremental heightmap search and caller-supplied unsafe stand positions. */
@Internal
public final class StuckRecovery {
    private static final int JUMP_AT = 30;
    private static final int NEAR_STEP_AT = 60;
    private static final int WIDE_STEP_AT = 160;
    private static final int RETRY_GAP = 40;
    private static final int MAX_FALL = 16;
    private static final int MAX_UNPROTECTED_FALL = 4;
    private static final int MAX_SEARCH_COLUMNS_PER_TICK = 4;
    private static final int MAX_UNDERGROUND_DESCENT = 10;
    private static final double STEP_SPEED = 0.30D;
    // Movement is measured over a window, not per tick: walking is ~0.216 blocks/tick and the
    // old per-tick 0.2 cut-off flagged sneaking (FollowOwnerGoal) and door/stair slowdowns as "stuck".
    private static final int MOVE_WINDOW_TICKS = 10;
    private static final double MOVE_WINDOW_MIN_BLOCKS = 0.35D;

    public static final class State {
        Vec3 lastPos = Vec3.ZERO;
        int stuckTicks;
        int attempts;
        long lastAttemptTick;
        BlockPos lastStepTarget;
        int stepTicks;
        boolean gaveUp;
        int windowTicks;
        double windowDistance;
        boolean windowMoved = true;
        private Predicate<BlockPos> avoidedStand = pos -> false;
        private List<BlockPos> searchColumns = List.of();
        private final List<BlockPos> candidates = new ArrayList<>();
        private BlockPos searchOrigin;
        private BlockPos searchTarget;
        private int searchRadius;
        private int searchIndex;
        private boolean searchActive;

        /** Host mods can reject edges, claims, hazards, or other locations from recovery's stand search. */
        public void setAvoidedStandPredicate(Predicate<BlockPos> predicate) {
            avoidedStand = predicate == null ? pos -> false : predicate;
            clearSearch();
        }

        public void avoid(BlockPos pos) {
            if (pos == null) return;
            Predicate<BlockPos> previous = avoidedStand;
            BlockPos immutable = pos.immutable();
            avoidedStand = candidate -> candidate.equals(immutable) || previous.test(candidate);
            clearSearch();
        }

        private void clearSearch() {
            searchColumns = List.of();
            candidates.clear();
            searchOrigin = null;
            searchTarget = null;
            searchRadius = 0;
            searchIndex = 0;
            searchActive = false;
        }
    }

    private record SearchResult(boolean complete, BlockPos best) { }

    /** @return true if recovery is actively searching or performed a corrective action this tick. */
    public static boolean tick(CompanionEntity c, State s) {
        if (!(c.level() instanceof ServerLevel level)) return false;
        boolean wantsToMove = !c.getNavigation().isDone();
        Vec3 now = c.position();
        s.windowTicks++;
        s.windowDistance += now.distanceTo(s.lastPos);
        if (s.windowTicks >= MOVE_WINDOW_TICKS) {
            s.windowMoved = s.windowDistance > MOVE_WINDOW_MIN_BLOCKS;
            s.windowTicks = 0;
            s.windowDistance = 0.0D;
        }
        boolean moved = s.windowMoved;
        s.lastPos = now;

        if (!wantsToMove) {
            s.stuckTicks = 0;
            s.gaveUp = false;
            s.clearSearch();
            return false;
        }
        if (moved) {
            s.stuckTicks = 0;
            s.gaveUp = false;
            s.clearSearch();
            return false;
        }

        s.stuckTicks++;
        BlockPos target = NavigationArbiter.target(c);
        BlockPos feet = c.blockPosition();
        if (target != null && feet.distSqr(target) <= 4.0D) {
            s.stuckTicks = 0;
            s.clearSearch();
            return false;
        }
        if (s.stuckTicks >= JUMP_AT && c.onGround()) c.getJumpControl().jump();

        boolean near = s.stuckTicks >= NEAR_STEP_AT && s.stuckTicks < WIDE_STEP_AT;
        boolean wide = s.stuckTicks >= WIDE_STEP_AT;
        if (!near && !wide && !s.searchActive) return false;
        if (!s.searchActive && level.getGameTime() - s.lastAttemptTick < RETRY_GAP) return false;
        if (!c.onGround()) return s.searchActive;

        int radius = wide ? 6 : 3;
        SearchResult result = advanceSearch(level, c, feet, target, radius, s);
        if (!result.complete()) return true; // keep the scan at four columns per real server tick

        s.lastAttemptTick = level.getGameTime();
        s.attempts++;
        if (result.best() != null) {
            forceStep(c, result.best());
            s.lastStepTarget = result.best();
            s.stepTicks = 10;
            s.stuckTicks = 0;
            s.clearSearch();
            if (target != null) NavigationArbiter.request(c, target, "stuck", 500, 1.1D);
            return true;
        }

        if (wide) {
            // A wide scan completed and found no safe standable cell; this is a real route failure.
            c.getNavigation().stop();
            s.stuckTicks = 0;
            s.gaveUp = true;
            s.attempts = 0;
            s.clearSearch();
            // Act on gaveUp instead of leaving the active task stuck until MAX_TOTAL_TICKS:
            // the route is unreachable, so release the work immediately with a readable reason.
            com.yaseen.npcmind.task.TaskManager.cancelForReflex(c, "stuck_unreachable");
        }
        return false;
    }

    public static void reset(State s) {
        s.stuckTicks = 0;
        s.attempts = 0;
        s.lastStepTarget = null;
        s.stepTicks = 0;
        s.gaveUp = false;
        s.windowTicks = 0;
        s.windowDistance = 0.0D;
        s.windowMoved = true;
        s.clearSearch();
    }

    private static SearchResult advanceSearch(ServerLevel level, CompanionEntity c, BlockPos feet,
                                              BlockPos target, int radius, State s) {
        boolean needsNewSearch = !s.searchActive || !Objects.equals(s.searchOrigin, feet)
                || !Objects.equals(s.searchTarget, target) || s.searchRadius != radius;
        if (needsNewSearch) {
            s.searchColumns = makeColumns(feet, radius);
            s.candidates.clear();
            s.searchOrigin = feet.immutable();
            s.searchTarget = target == null ? null : target.immutable();
            s.searchRadius = radius;
            s.searchIndex = 0;
            s.searchActive = true;
            s.lastAttemptTick = level.getGameTime();
        }

        int inspected = 0;
        while (s.searchIndex < s.searchColumns.size() && inspected < MAX_SEARCH_COLUMNS_PER_TICK) {
            BlockPos column = s.searchColumns.get(s.searchIndex++);
            inspected++;
            int x = column.getX(), z = column.getZ();
            if (!level.hasChunkAt(column)) continue;
            // Underground (caves, mineshafts) the heightmap surface sits ABOVE the companion and
            // `fall < 0` rejected every candidate, so recovery could never rescue it. When the
            // surface is above the feet, scan the column downward from the companion's own level.
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y > feet.getY()) {
                BlockPos underground = findStandableBelow(level, x, feet.getY() + 1, z);
                if (underground != null && s.avoidedStand.test(underground)) underground = null;
                if (underground != null && canStepToward(level, feet, underground)) {
                    s.candidates.add(underground);
                }
                continue;
            }
            int fall = feet.getY() - y;
            if (fall < 0 || fall > MAX_FALL) continue;
            if (fall > MAX_UNPROTECTED_FALL && !hasEmergencyLandingSupport(level, new BlockPos(x, y, z))
                    && !hasWaterBucket(c)) continue;
            BlockPos candidate = new BlockPos(x, y, z);
            if (s.avoidedStand.test(candidate) || !canStandAt(level, candidate)
                    || !canStepToward(level, feet, candidate)) continue;
            s.candidates.add(candidate.immutable());
        }

        if (s.searchIndex < s.searchColumns.size()) return new SearchResult(false, null);
        s.searchActive = false;
        if (s.candidates.isEmpty()) return new SearchResult(true, null);
        s.candidates.sort(Comparator
                .comparingDouble((BlockPos p) -> score(level, feet, target, p))
                .thenComparingDouble(p -> horizontalDistSqr(feet, p))
                .thenComparingInt(p -> Math.abs(feet.getY() - p.getY())));
        return new SearchResult(true, s.candidates.get(0));
    }

    /** Walks a column downward looking for a standable cell within the companion's reach band. */
    private static BlockPos findStandableBelow(ServerLevel level, int x, int startY, int z) {
        for (int dy = 0; dy >= -MAX_UNDERGROUND_DESCENT; dy--) {
            BlockPos candidate = new BlockPos(x, startY + dy, z);
            if (!level.hasChunkAt(candidate)) return null;
            if (canStandAt(level, candidate)) return candidate.immutable();
        }
        return null;
    }

    private static List<BlockPos> makeColumns(BlockPos feet, int radius) {
        List<BlockPos> columns = new ArrayList<>();
        int radiusSquared = radius * radius;
        BlockPos low = feet.offset(-radius, 0, -radius);
        BlockPos high = feet.offset(radius, 0, radius);
        for (BlockPos cursor : BlockPos.betweenClosed(low, high)) {
            int deltaX = cursor.getX() - feet.getX();
            int deltaZ = cursor.getZ() - feet.getZ();
            if ((deltaX == 0 && deltaZ == 0) || deltaX * deltaX + deltaZ * deltaZ > radiusSquared) {
                continue;
            }
            columns.add(new BlockPos(cursor.getX(), feet.getY(), cursor.getZ()));
        }
        return columns;
    }

    private static boolean hasEmergencyLandingSupport(ServerLevel level, BlockPos feet) {
        BlockState floor = level.getBlockState(feet.below());
        return floor.is(Blocks.HAY_BLOCK) || floor.is(Blocks.SLIME_BLOCK) || floor.is(Blocks.HONEY_BLOCK)
                || floor.is(BlockTags.BEDS) || floor.is(Blocks.COBWEB);
    }

    private static boolean hasWaterBucket(CompanionEntity c) {
        if (c.getMainHandItem().is(Items.WATER_BUCKET) || c.getOffhandItem().is(Items.WATER_BUCKET)) return true;
        for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
            if (c.getCompanionInventory().getItem(i).is(Items.WATER_BUCKET)) return true;
        }
        return false;
    }

    private static double score(ServerLevel level, BlockPos feet, BlockPos target, BlockPos candidate) {
        double score = horizontalDistSqr(feet, candidate) * 1.5D;
        if (target != null) score += candidate.distSqr(target) * 0.12D;
        if (candidate.getY() < feet.getY()) score += (feet.getY() - candidate.getY()) * 0.75D;
        if (level.canSeeSky(candidate.above())) score -= 2.0D;
        return score;
    }

    private static boolean canStandAt(ServerLevel level, BlockPos pos) {
        if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos) || !level.hasChunkAt(pos)) return false;
        BlockState here = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        BlockState floor = level.getBlockState(pos.below());
        if (!here.getCollisionShape(level, pos).isEmpty() || !head.getCollisionShape(level, pos.above()).isEmpty()) return false;
        if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.above()).isEmpty()) return false;
        if (here.is(BlockTags.FIRE) || floor.is(BlockTags.FIRE)) return false;
        if (floor.is(Blocks.CACTUS) || floor.is(Blocks.MAGMA_BLOCK)) return false;
        if (!level.getFluidState(pos.below()).isEmpty()) return false;
        return floor.isFaceSturdy(level, pos.below(), Direction.UP);
    }

    private static boolean canStepToward(ServerLevel level, BlockPos feet, BlockPos candidate) {
        Direction dir = firstStepDirection(feet, candidate);
        if (dir == null) return false;
        BlockPos adjacent = feet.relative(dir);
        if (!level.isInWorldBounds(adjacent) || !level.getWorldBorder().isWithinBounds(adjacent)) return false;
        if (!level.hasChunkAt(adjacent) || !level.hasChunkAt(adjacent.above())) return false;
        return level.getBlockState(adjacent).getCollisionShape(level, adjacent).isEmpty()
                && level.getBlockState(adjacent.above()).getCollisionShape(level, adjacent.above()).isEmpty()
                && level.getFluidState(adjacent).isEmpty() && level.getFluidState(adjacent.above()).isEmpty();
    }

    private static Direction firstStepDirection(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) return null;
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static void forceStep(CompanionEntity c, BlockPos step) {
        double tx = step.getX() + 0.5D, tz = step.getZ() + 0.5D;
        double dx = tx - c.getX(), dz = tz - c.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4D) return;
        c.getNavigation().stop();
        c.getLookControl().setLookAt(tx, c.getY(), tz, 30.0F, 30.0F);
        Vec3 motion = c.getDeltaMovement();
        c.setDeltaMovement(dx / len * STEP_SPEED, motion.y, dz / len * STEP_SPEED);
        c.hasImpulse = true;
    }

    private static double horizontalDistSqr(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
