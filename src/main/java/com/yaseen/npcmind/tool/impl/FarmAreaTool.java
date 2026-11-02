package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import com.yaseen.npcmind.api.Internal;

/** Real vanilla crop harvesting/replanting around the companion. No simulated farm state. */
@Internal
public final class FarmAreaTool implements AITool {
    public String name(){return "farm_area";}
    public String description(){return "Harvest mature real vanilla wheat, carrots, potatoes and beetroot crops nearby, then replant using real seeds/items from the companion inventory.";}
    public String schema(){return "{\"radius\":number(optional),\"replant\":boolean(optional)}";}
    public String execute(CompanionEntity c, ServerPlayer p, String a){
        if(!c.getPermissions().allowed(ActionPermissions.Action.FARM)) return "DENIED permission=farm";
        int radius=Math.max(1,Math.min(8,MoveToTool.num(a,"radius")==null?5:MoveToTool.num(a,"radius")));
        Boolean rb=JsonArgs.bool(a,"replant"); boolean replant=rb==null||rb;
        if (!(c.level() instanceof net.minecraft.server.level.ServerLevel level)) return "ERROR not_server";
        if (!(c.getOwnerPlayer() instanceof ServerPlayer)) return "DENIED owner is not online; world mutation is blocked";
        java.util.List<BlockPos> crops = new java.util.ArrayList<>();
        BlockPos base=c.blockPosition();
        for(BlockPos pos:BlockPos.betweenClosed(base.offset(-radius,-2,-radius),base.offset(radius,2,radius))){
            if (!level.hasChunkAt(pos)) continue;
            BlockState state=level.getBlockState(pos);
            if(state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) crops.add(pos.immutable());
            if (crops.size() >= 96) break;
        }
        if(crops.isEmpty()) return "FAILED no_mature_vanilla_crops_nearby radius="+radius;
        // Harvesting is a real task: the companion walks to each crop, checks reach and protection,
        // then breaks one mature crop per action. It no longer silently ignores crops outside reach.
        return com.yaseen.npcmind.task.TaskManager.harvestCrops(c, crops, replant);
    }

    /**
     * Consume one seed of the right type. The old helper returned the live inventory stack and
     * shrank it without clearing the slot at zero or calling setChanged(), which left phantom
     * zero-count stacks and could desync the client inventory.
     */
    private static boolean consumeSeed(CompanionEntity c, net.minecraft.world.level.block.Block block){
        net.minecraft.world.item.Item wanted = seedItemFor(block);
        if(wanted == null) return false;
        var inv = c.getCompanionInventory();
        for(int i=0;i<inv.getContainerSize();i++){
            ItemStack s=inv.getItem(i);
            if(s.is(wanted)){
                s.shrink(1);
                if(s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                inv.setChanged();
                return true;
            }
        }
        return false;
    }

    public static boolean hasSeedFor(CompanionEntity c, net.minecraft.world.level.block.Block block) {
        net.minecraft.world.item.Item wanted = seedItemFor(block);
        return c != null && wanted != null
                && (c.getMainHandItem().is(wanted) || findSeedSlotFor(c, block) >= 0);
    }

    /** Returns the inventory slot containing the right seed, or -1 if it isn't stored there. */
    public static int findSeedSlotFor(CompanionEntity c, net.minecraft.world.level.block.Block block) {
        net.minecraft.world.item.Item wanted = seedItemFor(block);
        if (wanted == null) return -1;
        for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
            ItemStack stack = c.getCompanionInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(wanted)) return i;
        }
        return -1;
    }

    public static boolean isSeedFor(net.minecraft.world.level.block.Block block, ItemStack stack) {
        net.minecraft.world.item.Item wanted = seedItemFor(block);
        return wanted != null && stack != null && !stack.isEmpty() && stack.is(wanted);
    }

    public static boolean consumeSeedFor(CompanionEntity c, net.minecraft.world.level.block.Block block) {
        return consumeSeed(c, block);
    }

    private static net.minecraft.world.item.Item seedItemFor(net.minecraft.world.level.block.Block block){
        return block==Blocks.WHEAT?Items.WHEAT_SEEDS:block==Blocks.CARROTS?Items.CARROT:block==Blocks.POTATOES?Items.POTATO:block==Blocks.BEETROOTS?Items.BEETROOT_SEEDS:null;
    }
}
