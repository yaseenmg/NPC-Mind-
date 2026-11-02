package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import com.yaseen.npcmind.api.Internal;

/** Local, visual sleep simulation for PathfinderMob companions (not ServerPlayer world-time skipping). */
@Internal
public final class SleepController {
    private static final int BED_SEARCH_RADIUS = 6;
    private static final int CHECK_INTERVAL_TICKS = 40;
    private static final int SLEEP_TICKS = 100;
    private static final int MAX_APPROACH_TICKS = 100;
    private static final int COOLDOWN_TICKS = 1200;

    /** Runtime claim registry: bed foot position -> sleeping companion UUID. */
    private static final java.util.Map<BlockPos, java.util.UUID> BED_CLAIMS = new java.util.concurrent.ConcurrentHashMap<>();

    public static final class State {
        private BlockPos bedFoot;
        private int sleepTicks;
        private int repathTicks;
        private int approachTicks;
        private long nextCheckGameTime;
        private long cooldownUntil;
    }

    private SleepController() { }

    /** Returns true while moving to a bed or simulating sleep. Does not skip time or affect nearby players. */
    public static boolean tick(CompanionEntity c, State state) {
        if (!(c.level() instanceof ServerLevel level) || !c.isAlive() || c.isRemoved()) return false;
        boolean night = isNight(level);

        if (state.bedFoot != null) {
            // Player parity: any recent hit is an instant wake-up, even from an archer farther
            // than the 16-block kernel scan radius (hasImmediateThreat scans 256.0D = 16^2).
            net.minecraft.world.entity.LivingEntity recent = c.getLastHurtByMob();
            int hurtAge = c.tickCount - c.getLastHurtByMobTimestamp();
            boolean recentAttack = recent != null && recent.isAlive() && hurtAge >= 0 && hurtAge <= 100;
            if (!night || recentAttack || (c.getTarget() != null && c.getTarget().isAlive()) || c.isOnFire()
                    || !isValidBed(level, state.bedFoot) || state.sleepTicks <= 0) {
                wake(c, state, level.getGameTime());
                return false;
            }
            BlockPos bed = state.bedFoot;
            double d2 = c.distanceToSqr(bed.getX() + 0.5D, bed.getY(), bed.getZ() + 0.5D);
            if (d2 > 4.0D) {
                if (++state.approachTicks > MAX_APPROACH_TICKS) {
                    wake(c, state, level.getGameTime());
                    return false;
                }
                if (state.repathTicks-- <= 0) {
                    c.getNavigation().moveTo(bed.getX() + 0.5D, bed.getY(), bed.getZ() + 0.5D, 1.0D);
                    state.repathTicks = 20;
                }
                return true;
            }
            state.approachTicks = 0;
            c.getNavigation().stop();
            BlockState foot = level.getBlockState(bed);
            Direction facing = foot.getValue(BedBlock.FACING);
            BlockPos head = bed.relative(facing);
            c.setYRot(facing.toYRot());
            c.yBodyRot = facing.toYRot();
            c.yHeadRot = facing.toYRot();
            if (c.getPose() != Pose.SLEEPING) {
                c.startSleeping(head);
                // Mobs do not participate in the player sleep/time-skip system; retain the pose as a visual simulation.
                if (c.getPose() != Pose.SLEEPING) c.setPose(Pose.SLEEPING);
            }
            state.sleepTicks--;
            return true;
        }

        if (!night || level.getGameTime() < state.cooldownUntil
                || level.getGameTime() < state.nextCheckGameTime
                || c.getTarget() != null || c.isInWaterOrBubble() || c.isPassenger()) return false;
        state.nextCheckGameTime = level.getGameTime() + CHECK_INTERVAL_TICKS;
        BlockPos bed = findNearbyBed(level, c.blockPosition(), c.getUUID());
        if (bed == null) return false;
        state.bedFoot = bed;
        state.sleepTicks = SLEEP_TICKS;
        state.repathTicks = 0;
        state.approachTicks = 0;
        state.cooldownUntil = level.getGameTime() + COOLDOWN_TICKS;
        // Claim the bed for this companion: LivingEntity.startSleeping never sets the vanilla
        // OCCUPIED flag (that is Player-only), so an explicit claim prevents two companions
        // from visually piling into the same bed.
        BED_CLAIMS.put(bed, c.getUUID());
        c.getNavigation().moveTo(bed.getX() + 0.5D, bed.getY(), bed.getZ() + 0.5D, 1.0D);
        return true;
    }

    private static boolean isNight(ServerLevel level) {
        long time = Math.floorMod(level.getDayTime(), 24000L);
        return time >= 13000L && time < 23000L;
    }

    private static BlockPos findNearbyBed(ServerLevel level, BlockPos origin, java.util.UUID companionId) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos candidate : BlockPos.betweenClosed(
                origin.offset(-BED_SEARCH_RADIUS, -2, -BED_SEARCH_RADIUS),
                origin.offset(BED_SEARCH_RADIUS, 2, BED_SEARCH_RADIUS))) {
            if (!level.hasChunkAt(candidate)) continue;
            BlockPos foot = normalizeFoot(level, candidate.immutable());
            if (foot == null || !isValidBed(level, foot) || isClaimedByOther(foot, companionId)) continue;
            double distance = origin.distSqr(foot);
            if (distance < bestDistance) {
                best = foot.immutable();
                bestDistance = distance;
            }
        }
        return best;
    }

    private static BlockPos normalizeFoot(ServerLevel level, BlockPos candidatePos) {
        if (!level.hasChunkAt(candidatePos)) return null;
        BlockState candidateState = level.getBlockState(candidatePos);
        if (!(candidateState.getBlock() instanceof BedBlock)) return null;
        if (!candidateState.hasProperty(BedBlock.PART) || !candidateState.hasProperty(BedBlock.FACING)) {
            return null;
        }

        Direction facing = candidateState.getValue(BedBlock.FACING);
        BlockPos footPos = candidateState.getValue(BedBlock.PART) == BedPart.FOOT
                ? candidatePos.immutable() : candidatePos.relative(facing.getOpposite());
        if (!level.hasChunkAt(footPos)) return null;

        BlockState footState = level.getBlockState(footPos);
        if (!(footState.getBlock() instanceof BedBlock)) return null;
        if (!footState.hasProperty(BedBlock.PART) || footState.getValue(BedBlock.PART) != BedPart.FOOT) {
            return null;
        }
        return footPos.immutable();
    }

    private static boolean isValidBed(ServerLevel level, BlockPos footPos) {
        if (!level.hasChunkAt(footPos)) return false;
        BlockState footState = level.getBlockState(footPos);
        if (!isBedHalf(footState, BedPart.FOOT) || footState.getValue(BedBlock.OCCUPIED)) return false;

        Direction forward = footState.getValue(BedBlock.FACING);
        BlockPos otherHalfPos = footPos.relative(forward);
        if (!level.hasChunkAt(otherHalfPos)) return false;
        BlockState otherHalf = level.getBlockState(otherHalfPos);
        if (!isBedHalf(otherHalf, BedPart.HEAD)) return false;
        return otherHalf.getBlock() == footState.getBlock()
                && otherHalf.getValue(BedBlock.FACING) == forward
                && !otherHalf.getValue(BedBlock.OCCUPIED);
    }

    /** True when the given foot position is already claimed by a different companion. */
    private static boolean isClaimedByOther(BlockPos footPos, java.util.UUID self) {
        java.util.UUID claim = BED_CLAIMS.get(footPos);
        return claim != null && !claim.equals(self);
    }

    /** Event-driven wake-up: any meaningful hurt event ends simulated sleep immediately. */
    public static void wakeOnHurt(CompanionEntity c) {
        if (c == null || c.level().isClientSide() || !(c.level() instanceof ServerLevel level)) return;
        State state = c.sleepState();
        if (state.bedFoot != null) wake(c, state, level.getGameTime());
    }

    /** Called by CompanionCleanup when a companion leaves the world so its claim cannot leak. */
    public static void releaseClaims(java.util.UUID id) {
        if (id == null) return;
        BED_CLAIMS.values().removeIf(claim -> claim.equals(id));
    }

    private static boolean isBedHalf(BlockState state, BedPart half) {
        return state.getBlock() instanceof BedBlock
                && state.hasProperty(BedBlock.PART)
                && state.hasProperty(BedBlock.FACING)
                && state.hasProperty(BedBlock.OCCUPIED)
                && state.getValue(BedBlock.PART) == half;
    }

    private static void wake(CompanionEntity c, State state, long now) {
        c.getNavigation().stop();
        if (c.getPose() == Pose.SLEEPING) c.stopSleeping();
        if (c.getPose() == Pose.SLEEPING) c.setPose(Pose.STANDING);
        if (state.bedFoot != null) BED_CLAIMS.remove(state.bedFoot, c.getUUID());
        state.bedFoot = null;
        state.sleepTicks = 0;
        state.repathTicks = 0;
        state.approachTicks = 0;
        state.nextCheckGameTime = now + CHECK_INTERVAL_TICKS;
    }
}
