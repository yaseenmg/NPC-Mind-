package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.crafting.InventoryOps;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.security.WorldActionSecurity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import com.yaseen.npcmind.api.Internal;

/** Bounded emergency water-bucket clutch. It never scans or loads chunks below the fall. */
@Internal
public final class WaterBucketClutch {
    private static final float MIN_FALL_DISTANCE = 3.0F;
    private static final double MIN_FALL_SPEED = -0.20D;
    private static final double GROUND_RAY = 6.0D;
    private static final double MAX_REACH_SQR = 4.5D * 4.5D;
    private static final int PICKUP_DELAY = 5;
    private static final int MAX_ACTIVE_TICKS = 80;

    public static final class State {
        private BlockPos placedWater;
        private int ticks;
        private int pickupDelay = -1;
        private boolean touchedWater;
        private long cooldownUntil;
    }

    private record BucketRef(int kind, int slot) { }
    // kind: 0 = companion inventory, 1 = main hand, 2 = off hand

    private WaterBucketClutch() { }

    /** @return true while the emergency clutch owns movement for the current tick. */
    public static boolean tick(CompanionEntity c, State state) {
        if (!(c.level() instanceof ServerLevel level) || !c.isAlive() || c.isRemoved()) return false;
        if (state.placedWater != null) return tickPlacedWater(c, level, state);
        if (level.getGameTime() < state.cooldownUntil || c.onGround() || c.isPassenger()
                || c.isInWaterOrBubble() || level.dimensionType().ultraWarm()
                || c.getDeltaMovement().y >= MIN_FALL_SPEED || c.fallDistance <= MIN_FALL_DISTANCE) {
            return false;
        }
        BucketRef waterBucket = findItem(c, Items.WATER_BUCKET);
        if (waterBucket == null || !canConvert(c, waterBucket, Items.BUCKET)) return false;
        if (!(c.getOwnerPlayer() instanceof ServerPlayer owner) || !c.getPermissions().allowed(
                com.yaseen.npcmind.state.ActionPermissions.Action.BUILD)) return false;

        BlockPos target = findPlacement(c, level);
        if (target == null || !canPlace(level, target) || !WorldActionSecurity.canPlaceBlock(c, owner, target)) return false;
        // Vanilla parity: water meeting a lava source becomes obsidian, and water meeting
        // flowing lava becomes cobblestone. Writing a water block over lava would bypass that
        // interaction, leave liquid water above the lava pit and waste the obsidian resource.
        BlockState targetState = level.getBlockState(target);
        if (!level.getFluidState(target).isEmpty() && level.getFluidState(target).is(FluidTags.LAVA)) {
            boolean lavaSource = level.getFluidState(target).isSource();
            BlockState converted = lavaSource ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
            if (!level.setBlockAndUpdate(target, converted)) return false;
            if (!convertOne(c, waterBucket, Items.WATER_BUCKET, Items.BUCKET)) {
                // The bucket could not be consumed: undo the conversion instead of keeping it.
                level.setBlockAndUpdate(target, targetState);
                return false;
            }
            level.playSound(null, target, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.0F, 1.0F);
            state.cooldownUntil = level.getGameTime() + 40L;
            c.getNavigation().stop();
            c.getLookControl().setLookAt(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, 70.0F, 70.0F);
            c.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
            return true;
        }
        BlockState original = targetState;
        if (!level.setBlockAndUpdate(target, Blocks.WATER.defaultBlockState())) return false;
        if (!convertOne(c, waterBucket, Items.WATER_BUCKET, Items.BUCKET)) {
            // Restore what was actually there before (air, replaceable plant...) — never AIR,
            // which would erase the original block and leave a hole in the world.
            level.setBlockAndUpdate(target, original);
            return false;
        }
        state.placedWater = target.immutable();
        state.ticks = 0;
        state.pickupDelay = -1;
        state.touchedWater = false;
        state.cooldownUntil = level.getGameTime() + 40L;
        c.getNavigation().stop();
        c.getLookControl().setLookAt(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, 70.0F, 70.0F);
        c.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        level.playSound(null, target, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
        return true;
    }

    private static boolean tickPlacedWater(CompanionEntity c, ServerLevel level, State s) {
        s.ticks++;
        BlockPos pos = s.placedWater;
        if (s.ticks > MAX_ACTIVE_TICKS || !level.hasChunkAt(pos)
                || !level.getFluidState(pos).is(FluidTags.WATER)
                || !level.getFluidState(pos).isSource()) {
            reset(s);
            return false;
        }
        c.getNavigation().stop();
        c.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 70.0F, 70.0F);
        boolean touching = c.isInWaterOrBubble() || c.getBoundingBox().intersects(new AABB(pos));
        if (!s.touchedWater && touching) {
            s.touchedWater = true;
            s.pickupDelay = PICKUP_DELAY;
        }
        if (s.touchedWater) {
            if (s.pickupDelay > 0) s.pickupDelay--;
            else {
                BucketRef emptyBucket = findItem(c, Items.BUCKET);
                if (emptyBucket != null && canConvert(c, emptyBucket, Items.WATER_BUCKET)
                        && c.distanceToSqr(Vec3.atCenterOf(pos)) <= MAX_REACH_SQR) {
                    if (convertOne(c, emptyBucket, Items.BUCKET, Items.WATER_BUCKET)) {
                        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                        level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
                        // Removing only the source left the clutch's own flowing water streaming
                        // around the landing spot. Clear the leftover flow the clutch created.
                        for (BlockPos flow : BlockPos.betweenClosed(pos.offset(-2, -1, -2), pos.offset(2, 1, 2))) {
                            var fs = level.getFluidState(flow);
                            if (!fs.isEmpty() && fs.is(FluidTags.WATER) && !fs.isSource()) {
                                level.setBlockAndUpdate(flow, Blocks.AIR.defaultBlockState());
                            }
                        }
                    }
                    reset(s);
                    return false;
                }
            }
        }
        return true;
    }

    private static BlockPos findPlacement(CompanionEntity c, ServerLevel level) {
        Vec3 motion = c.getDeltaMovement();
        Vec3 start = new Vec3(c.getX() + motion.x, c.getBoundingBox().minY + 0.05D, c.getZ() + motion.z);
        Vec3 end = start.add(0.0D, -GROUND_RAY, 0.0D);
        BlockPos startPos = BlockPos.containing(start);
        BlockPos endPos = BlockPos.containing(end);
        if (!level.hasChunkAt(startPos) || !level.hasChunkAt(endPos)) return null;
        BlockHitResult hit = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, c));
        if (hit.getType() == HitResult.Type.MISS) return null;
        double distance = start.y - hit.getLocation().y;
        double placementDistance = Mth.clamp(-motion.y + 1.25D, 1.5D, 5.5D);
        // The outer gate already guarantees fallDistance > MIN_FALL_DISTANCE, so distance alone
        // decides: the previous fallDistance + distance condition was unreachable dead code.
        if (distance < 0.0D || distance > placementDistance) return null;
        BlockPos target = hit.getBlockPos().above();
        if (c.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) > MAX_REACH_SQR) return null;
        return target;
    }

    private static boolean canPlace(ServerLevel level, BlockPos pos) {
        if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)
                || !level.hasChunkAt(pos) || level.dimensionType().ultraWarm()) return false;
        if (level.getFluidState(pos).is(FluidTags.WATER)) return false;
        if (!level.getFluidState(pos).isEmpty() && !level.getFluidState(pos).is(FluidTags.LAVA)) return false;
        BlockState state = level.getBlockState(pos);
        if (!state.isAir() && !state.canBeReplaced() && !level.getFluidState(pos).is(FluidTags.LAVA)) return false;
        return !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty();
    }

    private static BucketRef findItem(CompanionEntity c, net.minecraft.world.item.Item item) {
        for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
            if (c.getCompanionInventory().getItem(i).is(item)) return new BucketRef(0, i);
        }
        if (c.getMainHandItem().is(item)) return new BucketRef(1, -1);
        if (c.getOffhandItem().is(item)) return new BucketRef(2, -1);
        return null;
    }

    private static ItemStack stackAt(CompanionEntity c, BucketRef ref) {
        return switch (ref.kind()) {
            case 0 -> c.getCompanionInventory().getItem(ref.slot());
            case 1 -> c.getMainHandItem();
            default -> c.getOffhandItem();
        };
    }

    private static void setAt(CompanionEntity c, BucketRef ref, ItemStack stack) {
        switch (ref.kind()) {
            case 0 -> c.getCompanionInventory().setItem(ref.slot(), stack);
            case 1 -> c.setItemSlot(EquipmentSlot.MAINHAND, stack);
            default -> c.setItemSlot(EquipmentSlot.OFFHAND, stack);
        }
    }

    private static boolean canConvert(CompanionEntity c, BucketRef ref, net.minecraft.world.item.Item output) {
        ItemStack source = stackAt(c, ref);
        return !source.isEmpty() && (source.getCount() == 1
                || InventoryOps.canAdd(c.getCompanionInventory(), new ItemStack(output)));
    }

    private static boolean convertOne(CompanionEntity c, BucketRef ref,
                                      net.minecraft.world.item.Item expected, net.minecraft.world.item.Item output) {
        ItemStack source = stackAt(c, ref);
        if (source.isEmpty() || !source.is(expected) || !canConvert(c, ref, output)) return false;
        if (source.getCount() == 1) {
            setAt(c, ref, new ItemStack(output));
            return true;
        }
        source.shrink(1);
        return InventoryOps.add(c.getCompanionInventory(), new ItemStack(output));
    }

    private static void reset(State s) {
        s.placedWater = null;
        s.ticks = 0;
        s.pickupDelay = -1;
        s.touchedWater = false;
    }
}
