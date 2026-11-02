package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.autonomy.NavigationArbiter;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.task.TaskManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import com.yaseen.npcmind.api.Internal;

/**
 * Top-priority environmental survival layer (priority 0, above combat, tasks and autonomy).
 * Handles lava, fire, drowning and being stuck while travelling. Returns true while it owns the
 * entity so lower layers (combat/tasks) are skipped for that tick.
 */
@Internal
public final class SurvivalGuard {
    static final int PERIOD = 5;

    // Per-companion state is kept on the instance through this small holder.
    public static final class State {
        Vec3 lastPos = Vec3.ZERO;
        int stuckTicks;
        int hazardTicks;
        long lastWarn;
        final StuckRecovery.State recovery = new StuckRecovery.State();
        final WaterBucketClutch.State waterBucketClutch = new WaterBucketClutch.State();
    }

    public static boolean tick(CompanionEntity c, State s) {
        Level level = c.level();
        if (level.isClientSide() || !c.isAlive()) return false;

        // Bucket clutch is a one-tick reflex: delaying it to the 5-tick hazard cadence misses falls.
        if (WaterBucketClutch.tick(c, s.waterBucketClutch)) return true;

        // The validated stuck detector runs every tick so its bounded column budget is truly per-tick.
        // Environmental cube scans remain on the lower-cost periodic cadence below.
        if (c.tickCount % PERIOD != 0) {
            boolean recovering = s.hazardTicks <= 0 && StuckRecovery.tick(c, s.recovery);
            s.stuckTicks = recovering ? 0 : s.recovery.stuckTicks;
            return s.hazardTicks > 0 || recovering;
        }

        boolean inLava = c.isInLava();
        boolean burning = c.isOnFire() && !c.fireImmune();
        boolean drowning = c.isEyeInFluid(FluidTags.WATER) && c.getAirSupply() < 120;
        boolean inFireBlock = isFire(level.getBlockState(c.blockPosition()));

        if (inLava || burning || drowning || inFireBlock) {
            s.hazardTicks = 20;
            // A fight is not worth dying for.
            TaskManager.cancelForReflex(c, inLava ? "lava" : (drowning ? "drowning" : "fire"));
            if (inLava || drowning) c.setTarget(null);
            if (c.isUsingItem() && !c.isEating()) c.stopUsingItem();
            BlockPos safe = findSafe(c, inLava || inFireBlock || (burning && !c.isInWaterOrBubble()), burning);
            if (safe != null) {
                NavigationArbiter.request(c, safe, "survival", 1000, 1.4D);
                c.getMoveControl().setWantedPosition(safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5, 1.4D);
            }
            if (inLava || (drowning && c.isInWater())) c.getJumpControl().jump();
            if (c.tickCount - s.lastWarn > 200) {
                s.lastWarn = c.tickCount;
                if (inLava) c.say("Warning: I am in danger!");
                else if (drowning) c.say("I am in danger!");
                else if (burning) c.say("I'm on fire!");
            }
            return true;
        }
        if (s.hazardTicks > 0) s.hazardTicks -= PERIOD;

        // Include the cadence boundary too: stuck detection should use real tick counts, not 5-tick chunks.
        boolean recovered = StuckRecovery.tick(c, s.recovery);
        s.stuckTicks = recovered ? 0 : s.recovery.stuckTicks;
        return recovered;
    }

    private static boolean isFire(BlockState st) {
        return st.is(BlockTags.FIRE);
    }

    /** Nearest standable, non-hazardous block. When burning (and not in lava) water is preferred. */
    private static BlockPos findSafe(CompanionEntity c, boolean dryLandOnly, boolean wantWater) {
        Level level = c.level();
        BlockPos origin = c.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int r = 7;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    double score = dx * dx + dz * dz + dy * dy * 2.0D;
                    boolean water = level.getFluidState(p).is(FluidTags.WATER) && !level.getFluidState(p.above()).is(FluidTags.LAVA);
                    if (wantWater && !dryLandOnly && water) {
                        score -= 20.0D; // a water block puts the fire out
                    } else if (!isStandable(level, p)) {
                        continue;
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    private static boolean isStandable(Level level, BlockPos feet) {
        BlockPos below = feet.below();
        if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty()) return false;
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) return false;
        if (!level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) return false;
        if (isFire(level.getBlockState(feet)) || level.getBlockState(feet).is(net.minecraft.world.level.block.Blocks.CACTUS)) return false;
        BlockState floor = level.getBlockState(below);
        if (floor.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK) || floor.is(net.minecraft.world.level.block.Blocks.CACTUS)) return false;
        if (!level.getFluidState(below).isEmpty()) return false;
        return floor.isFaceSturdy(level, below, Direction.UP);
    }

    private SurvivalGuard() {}
}
