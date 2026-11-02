package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.security.WorldActionSecurity;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.crafting.InventoryOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import com.yaseen.npcmind.api.Internal;

/** Fill a real empty bucket from a nearby loaded source-water block. */
@Internal
public final class FillWaterBucketTool implements AITool {
    @Override public String name() { return "fill_bucket"; }
    @Override public String description() { return "Fill one empty bucket from a nearby source-water block, consuming the source like a normal bucket action. Requires BUILD permission and a loaded, accessible block."; }
    @Override public String schema() { return "{\"fluid\":\"water(optional)\"}"; }

    @Override
    public String execute(CompanionEntity c, ServerPlayer player, String arguments) {
        if (!(c.level() instanceof ServerLevel level) || player == null || player.level() != c.level()) {
            return "DENIED server_level_or_owner_mismatch";
        }
        if (!c.getPermissions().allowed(ActionPermissions.Action.BUILD)) return "DENIED permission=build";
        BucketRef empty = findItem(c, Items.BUCKET);
        if (empty == null) return "FAILED no_empty_bucket";
        if (!canConvert(c, empty, Items.WATER_BUCKET)) return "FAILED no_inventory_space_for_filled_bucket";
        BlockPos origin = c.blockPosition();
        BlockPos source = null;
        double best = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-4, -2, -4), origin.offset(4, 2, 4))) {
            // Only remove a standalone source-water block. A waterlogged chest, leaves or
            // another waterlogged block is not a bucket source block and must never be erased.
            if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(Blocks.WATER)
                    || !level.getFluidState(pos).is(FluidTags.WATER)
                    || !level.getFluidState(pos).isSource()) continue;
            Vec3 eye = c.getEyePosition();
            Vec3 center = Vec3.atCenterOf(pos);
            double distance = eye.distanceToSqr(center);
            if (distance > 4.5D * 4.5D || distance >= best
                    || !WorldActionSecurity.canInteractWithBlock(c, player, pos)) continue;
            var hit = level.clip(new ClipContext(eye, center, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.SOURCE_ONLY, c));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) continue;
            source = pos.immutable(); best = distance;
        }
        if (source == null) return "FAILED no_accessible_source_water_within_reach";
        if (!level.setBlockAndUpdate(source, Blocks.AIR.defaultBlockState())) return "FAILED source_water_removal_rejected";
        if (!convertOne(c, empty, Items.BUCKET, Items.WATER_BUCKET)) {
            level.setBlockAndUpdate(source, Blocks.WATER.defaultBlockState());
            return "FAILED bucket_conversion_failed; source_restored=true";
        }
        // Vanilla BucketItem fills use the PLAYERS sound channel, not BLOCKS.
        level.playSound(null, source, SoundEvents.BUCKET_FILL, SoundSource.PLAYERS, 1.0F, 1.0F);
        return "SUCCESS filled_water_bucket=true source=" + source.getX() + "," + source.getY() + "," + source.getZ();
    }

    private record BucketRef(int kind, int slot) { }
    private static BucketRef findItem(CompanionEntity c, net.minecraft.world.item.Item item) {
        for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
            if (c.getCompanionInventory().getItem(i).is(item)) return new BucketRef(0, i);
        }
        if (c.getMainHandItem().is(item)) return new BucketRef(1, -1);
        if (c.getOffhandItem().is(item)) return new BucketRef(2, -1);
        return null;
    }
    private static ItemStack get(CompanionEntity c, BucketRef ref) {
        return switch (ref.kind()) { case 0 -> c.getCompanionInventory().getItem(ref.slot()); case 1 -> c.getMainHandItem(); default -> c.getOffhandItem(); };
    }
    private static void set(CompanionEntity c, BucketRef ref, ItemStack stack) {
        switch (ref.kind()) { case 0 -> c.getCompanionInventory().setItem(ref.slot(), stack); case 1 -> c.setItemSlot(EquipmentSlot.MAINHAND, stack); default -> c.setItemSlot(EquipmentSlot.OFFHAND, stack); }
    }
    private static boolean canConvert(CompanionEntity c, BucketRef ref, net.minecraft.world.item.Item output) {
        ItemStack in = get(c, ref);
        return !in.isEmpty() && (in.getCount() == 1 || InventoryOps.canAdd(c.getCompanionInventory(), new ItemStack(output)));
    }
    private static boolean convertOne(CompanionEntity c, BucketRef ref, net.minecraft.world.item.Item input, net.minecraft.world.item.Item output) {
        ItemStack in = get(c, ref);
        if (in.isEmpty() || !in.is(input) || !canConvert(c, ref, output)) return false;
        if (in.getCount() == 1) { set(c, ref, new ItemStack(output)); return true; }
        in.shrink(1);
        return InventoryOps.add(c.getCompanionInventory(), new ItemStack(output));
    }
}
