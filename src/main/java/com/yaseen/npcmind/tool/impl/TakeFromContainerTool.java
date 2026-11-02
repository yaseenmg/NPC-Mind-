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
public final class TakeFromContainerTool implements AITool {
    public String name(){return "take_from_container";}
    public String description(){return "Move real items from a real nearby Container into the companion's persistent inventory. Coordinates and item id are optional; when omitted, the nearest accessible container and first available item are selected.";}
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
            Container probe = DepositItemTool.containerAt(c, pos);
            if(probe != null){
                for(int i=0;i<probe.getContainerSize();i++){
                    ItemStack stack=probe.getItem(i);
                    if(!stack.isEmpty()){id=ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();break;}
                }
            }
        }
        int count=requested==null?64:requested;
        if(id==null||count<=0)return "FAILED no_item_in_container_or_invalid_count";
        ResourceLocation rl=ResourceLocation.tryParse(id); if(rl==null)return "ERROR invalid item_id";
        var item=ForgeRegistries.ITEMS.getValue(rl); if(item==null)return "ERROR unknown item_id "+id;
        if(!WorldActionSecurity.canAccessContainer(c,p,pos)) return "DENIED companion must be within 8 blocks of the container and the owner must have permission to interact";
        if(!c.level().isLoaded(pos))return "ERROR target is not loaded";
        Container src = DepositItemTool.containerAt(c, pos);
        if(src == null)return "ERROR no real Container at "+pos;
        int remaining=count;
        for(int i=0;i<src.getContainerSize()&&remaining>0;i++){
            ItemStack stack=src.getItem(i);
            if(stack.isEmpty()||stack.getItem()!=item)continue;
            int take=Math.min(remaining,stack.getCount());
            ItemStack moving=stack.copy(); moving.setCount(take);
            ItemStack left=com.yaseen.npcmind.crafting.InventoryOps.addAndReturnRemainder(c.getCompanionInventory(), moving);
            int inserted=take-left.getCount();
            if(inserted>0){stack.shrink(inserted);remaining-=inserted;}
        }
        c.getCompanionInventory().setChanged(); src.setChanged();
        int moved = count - remaining;
        if (moved <= 0) return "FAILED no_items_taken item="+id+" requested="+count;
        return remaining > 0 ? "PARTIAL taken="+moved+" requested="+count+" item="+id+" remaining="+remaining
                : "SUCCESS taken="+moved+" item="+id;
    }
}
