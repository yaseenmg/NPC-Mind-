package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.security.WorldActionSecurity;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.yaseen.npcmind.api.Internal;

/**
 * "start a farm" from scratch: till nearby grass/dirt into farmland and plant the seeds/crops the companion
 * owns (wheat, carrots, potatoes, beetroot). Coordinate-free — it works on the ground around it.
 * Harvesting + replanting of grown crops is handled by {@link FarmAreaTool}.
 */
@Internal
public final class TillAndPlantTool implements AITool {
    public String name(){ return "till_and_plant"; }
    public String description(){ return "Prepare farmland and plant crops around the companion with NO coordinates. It converts nearby grass/dirt to farmland and plants wheat/carrot/potato/beetroot from its own inventory. Needs seeds; a hoe is used if present."; }
    public String schema(){ return "{\"radius\":number(optional)}"; }

    public String execute(CompanionEntity c, ServerPlayer p, String a){
        if(!c.getPermissions().allowed(ActionPermissions.Action.FARM)) return "DENIED permission=farm";
        if(!(c.level() instanceof ServerLevel level)) return "ERROR not_server";
        if(!(c.getOwnerPlayer() instanceof ServerPlayer owner)) return "DENIED owner is not online; world mutation is blocked";
        Integer rad = JsonArgs.integer(a, "radius");
        int radius = rad == null ? 4 : Math.max(1, Math.min(8, rad));

        BlockPos base = c.blockPosition();
        // Collect candidate ground cells, then hand them to the progressive TillTask. The old
        // implementation tilled and planted the entire radius in a single tick from one spot,
        // with no reach check and no walking.
        java.util.List<BlockPos> ground = new java.util.ArrayList<>();
        for(BlockPos pos : BlockPos.betweenClosed(base.offset(-radius,-1,-radius), base.offset(radius,1,radius))){
            if(!level.isLoaded(pos)) continue;
            BlockState st = level.getBlockState(pos);
            BlockState above = level.getBlockState(pos.above());
            boolean tillable = st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.FARMLAND);
            if(!tillable || !above.isAir()) continue;
            if(!WorldActionSecurity.canPlaceBlock(c, owner, pos)) continue;
            ground.add(pos.immutable());
            if(ground.size() >= 96) break;
        }
        if(ground.isEmpty()) return "FAILED no_tillable_ground radius=" + radius;
        return com.yaseen.npcmind.task.TaskManager.tillAndPlant(c, ground, radius);
    }

    /** Choose a crop block the companion can actually plant from the seeds it holds. */
    public static Block pickCropFor(CompanionEntity c){
        if(hasItem(c, Items.WHEAT_SEEDS)) return Blocks.WHEAT;
        if(hasItem(c, Items.CARROT)) return Blocks.CARROTS;
        if(hasItem(c, Items.POTATO)) return Blocks.POTATOES;
        if(hasItem(c, Items.BEETROOT_SEEDS)) return Blocks.BEETROOTS;
        return null;
    }

    public static void consumeSeedFor(CompanionEntity c, Block crop){
        if(crop == Blocks.WHEAT) take(c, Items.WHEAT_SEEDS);
        else if(crop == Blocks.CARROTS) take(c, Items.CARROT);
        else if(crop == Blocks.POTATOES) take(c, Items.POTATO);
        else if(crop == Blocks.BEETROOTS) take(c, Items.BEETROOT_SEEDS);
    }

    public static boolean isSeedForCrop(Block crop, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        net.minecraft.world.item.Item wanted = crop == Blocks.WHEAT ? Items.WHEAT_SEEDS
                : crop == Blocks.CARROTS ? Items.CARROT
                : crop == Blocks.POTATOES ? Items.POTATO
                : crop == Blocks.BEETROOTS ? Items.BEETROOT_SEEDS : null;
        return wanted != null && stack.is(wanted);
    }

    private static boolean hasItem(CompanionEntity c, net.minecraft.world.item.Item item){
        if (c.getMainHandItem().is(item)) return true;
        for(int i=0;i<c.getCompanionInventory().getContainerSize();i++){
            ItemStack s = c.getCompanionInventory().getItem(i);
            if(!s.isEmpty() && s.getItem() == item) return true;
        }
        return false;
    }

    private static void take(CompanionEntity c, net.minecraft.world.item.Item item){
        for(int i=0;i<c.getCompanionInventory().getContainerSize();i++){
            ItemStack s = c.getCompanionInventory().getItem(i);
            if(!s.isEmpty() && s.getItem() == item){ c.getCompanionInventory().removeItem(i, 1); return; }
        }
    }
}
