package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.tool.AITool;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import com.yaseen.npcmind.api.Internal;

/** Transfers real items from the NPC's persistent inventory to the requesting player. */
@Internal
public final class GiveItemTool implements AITool {
    public String name(){ return "give_item_to_owner"; }
    public String description(){ return "Give a real item from the NPC's persistent inventory directly to the requesting player. Never create items from nothing."; }
    public String schema(){ return "{\"item_id\":string,\"count\":number(optional)}"; }

    public String execute(CompanionEntity c, ServerPlayer p, String a){
        if(c.getOwner()==null || !c.getOwner().equals(p.getUUID())) return "DENIED only the companion's owner may receive items";
        Integer count=MoveToTool.num(a,"count");
        String id=MoveToTool.str(a,"item_id");
        if(count==null||count<=0) count=1;
        if(id==null||id.isBlank()) return "ERROR item_id is required; inspect_companion_inventory first if the requested item is ambiguous";
        ResourceLocation rl=ResourceLocation.tryParse(id);
        if(rl==null) return "ERROR invalid item_id";
        Item item=ForgeRegistries.ITEMS.getValue(rl);
        if(item==null) return "ERROR unknown item_id "+id;

        int remaining=count, moved=0;
        for(int i=0;i<c.getCompanionInventory().getContainerSize()&&remaining>0;i++){
            ItemStack src=c.getCompanionInventory().getItem(i);
            if(src.isEmpty()||src.getItem()!=item) continue;
            int take=Math.min(remaining,src.getCount());
            ItemStack moving=src.copy(); moving.setCount(take);
            ItemStack left=insertIntoPlayer(p,moving);
            int inserted=take-left.getCount();
            if(inserted>0){src.shrink(inserted); remaining-=inserted; moved+=inserted;}
        }
        c.getCompanionInventory().setChanged();
        p.getInventory().setChanged();
        if(moved==0) return "FAILED no_item_transferred_to_player item="+id+" requested="+count+" reason=player_inventory_full_or_no_matching_stock";
        return remaining > 0 ? "PARTIAL GAVE item="+id+" moved="+moved+" remaining_requested="+remaining
                : "SUCCESS GAVE item="+id+" moved="+moved;
    }

    private static ItemStack insertIntoPlayer(ServerPlayer p, ItemStack stack){
        ItemStack remaining=stack.copy();
        // Player inventory semantics: merge with existing stacks first, then empty slots.
        for(int i=0;i<p.getInventory().getContainerSize()&&!remaining.isEmpty();i++){
            ItemStack slot=p.getInventory().getItem(i);
            if(slot.isEmpty()||!ItemStack.isSameItemSameTags(slot,remaining)) continue;
            int room=slot.getMaxStackSize()-slot.getCount();
            if(room<=0) continue;
            int n=Math.min(room,remaining.getCount());
            slot.grow(n); remaining.shrink(n);
        }
        for(int i=0;i<p.getInventory().getContainerSize()&&!remaining.isEmpty();i++){
            if(!p.getInventory().getItem(i).isEmpty()) continue;
            int n=Math.min(remaining.getCount(),remaining.getMaxStackSize());
            p.getInventory().setItem(i,remaining.split(n));
        }
        // Preserve the remainder when the owner's inventory is full. The caller only removes
        // the portion actually inserted; never turn a requested transfer into an unreported drop.
        return remaining;
    }
}
