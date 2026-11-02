package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.security.WorldActionSecurity;
import com.yaseen.npcmind.tool.AITool;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import com.yaseen.npcmind.api.Internal;

@Internal
public final class DepositItemTool implements AITool {
    public String name(){return "deposit_item";}
    public String description(){return "Move real items from the companion inventory into a real nearby Container. Coordinates and item id are optional; when omitted, the nearest accessible container and a sensible item are selected.";}
    public String schema(){return "{\"x\":number(optional),\"y\":number(optional),\"z\":number(optional),\"item_id\":string(optional),\"count\":number(optional)}";}
    public String execute(CompanionEntity c, ServerPlayer p, String a){
        Integer x=MoveToTool.num(a,"x"),y=MoveToTool.num(a,"y"),z=MoveToTool.num(a,"z"),requested=MoveToTool.num(a,"count");
        String id=MoveToTool.str(a,"item_id");
        BlockPos pos;
        if (x != null || y != null || z != null) {
            if (x == null || y == null || z == null) return "ERROR coordinates_require_x_y_z";
            pos = new BlockPos(x, y, z);
        } else pos=ContainerTargetFinder.nearest(c,p,8);
        if(pos==null)return "FAILED no_accessible_container_nearby";
        if(id==null||id.isBlank()){
            for(int i=0;i<c.getCompanionInventory().getContainerSize();i++){
                ItemStack stack=c.getCompanionInventory().getItem(i);
                if(!stack.isEmpty()){id=ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();break;}
            }
        }
        int count=requested==null?64:requested;
        if(id==null||count<=0)return "FAILED no_inventory_item_or_invalid_count";
        ResourceLocation rl=ResourceLocation.tryParse(id); if(rl==null)return "ERROR invalid item_id";
        var item=ForgeRegistries.ITEMS.getValue(rl); if(item==null)return "ERROR unknown item_id "+id;
        if(!WorldActionSecurity.canAccessContainer(c,p,pos)) return "DENIED companion must be within 8 blocks of the container and the owner must have permission to interact";
        if(!c.level().isLoaded(pos))return "ERROR target is not loaded";
        Container dst = containerAt(c, pos);
        if(dst == null)return "ERROR no real Container at "+pos;
        int remaining=count;
        for(int i=0;i<c.getCompanionInventory().getContainerSize()&&remaining>0;i++){
            ItemStack src=c.getCompanionInventory().getItem(i);
            if(src.isEmpty()||src.getItem()!=item)continue;
            int take=Math.min(remaining,src.getCount());
            ItemStack moving=src.copy(); moving.setCount(take);
            ItemStack left = insertIntoContainer(dst, moving);
            int inserted=take-left.getCount();
            if(inserted>0){src.shrink(inserted);remaining-=inserted;}
        }
        c.getCompanionInventory().setChanged(); dst.setChanged();
        int moved = count - remaining;
        if (moved <= 0) return "FAILED no_items_deposited item="+id+" requested="+count;
        return remaining > 0 ? "PARTIAL deposited="+moved+" requested="+count+" item="+id+" remaining="+remaining
                : "SUCCESS deposited="+moved+" item="+id;
    }
    /**
     * Full container at a position. For a double chest, getBlockEntity() returns one half only;
     * ChestBlock.getContainer() returns the combined 54-slot inventory the player sees.
     */
    static Container containerAt(CompanionEntity c, BlockPos pos) {
        var state = c.level().getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            return ChestBlock.getContainer(chestBlock, state, c.level(), pos, true);
        }
        return c.level().getBlockEntity(pos) instanceof Container container ? container : null;
    }
    /** Insert into a vanilla Container using the 1.20.1 Container API. */
    private static ItemStack insertIntoContainer(Container dst, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < dst.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slot = dst.getItem(i);
            if (slot.isEmpty()) continue;
            if (!ItemStack.isSameItemSameTags(slot, remaining) || !dst.canPlaceItem(i, remaining)) continue;
            int max = Math.min(dst.getMaxStackSize(), slot.getMaxStackSize());
            int move = Math.min(remaining.getCount(), Math.max(0, max - slot.getCount()));
            if (move > 0) {
                slot.grow(move);
                remaining.shrink(move);
                dst.setItem(i, slot);
            }
        }
        for (int i = 0; i < dst.getContainerSize() && !remaining.isEmpty(); i++) {
            if (!dst.getItem(i).isEmpty() || !dst.canPlaceItem(i, remaining)) continue;
            int move = Math.min(remaining.getCount(), Math.min(dst.getMaxStackSize(), remaining.getMaxStackSize()));
            ItemStack placed = remaining.copy();
            placed.setCount(move);
            dst.setItem(i, placed);
            remaining.shrink(move);
        }
        return remaining;
    }

}
