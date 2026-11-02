package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.knowledge.ModpackKnowledge;
import com.yaseen.npcmind.tool.AITool;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import com.yaseen.npcmind.api.Internal;

@Internal
public final class InspectCompanionInventoryTool implements AITool {
    public String name(){ return "inspect_companion_inventory"; }
    public String description(){ return "Read the companion's real persistent 36-slot inventory."; }
    public String schema(){ return "{}"; }
    public String execute(CompanionEntity c, ServerPlayer p, String a){
        Container inv=c.getCompanionInventory();
        StringBuilder b=new StringBuilder("REAL_COMPANION_INVENTORY\n");
        for(int i=0;i<inv.getContainerSize();i++){
            ItemStack s=inv.getItem(i);
            if(!s.isEmpty()) b.append(i).append('=').append(ModpackKnowledge.itemInfo(s)).append(" x").append(s.getCount()).append('\n');
        }
        return "RESULT " + b;
    }
}
