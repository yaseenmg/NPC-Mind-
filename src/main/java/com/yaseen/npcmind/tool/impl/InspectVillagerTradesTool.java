package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.tool.AITool;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import com.yaseen.npcmind.api.Internal;

@Internal
public final class InspectVillagerTradesTool implements AITool {
    public String name(){return "inspect_villager_trades";}
    public String description(){return "Inspect real offers of the nearest loaded Villager and report costs, output, stock, demand and whether the companion can currently afford them.";}
    public String schema(){return "{\"radius\":number(optional)}";}
    public String execute(CompanionEntity c, ServerPlayer p, String a){
        int radius=Math.max(2,Math.min(32,MoveToTool.num(a,"radius") == null ? 16 : MoveToTool.num(a,"radius")));
        if (!(c.level() instanceof ServerLevel level)) return "FAILED inspection requires server level";
        Villager v=level.getEntitiesOfClass(Villager.class,c.getBoundingBox().inflate(radius),x->x.isAlive()).stream().min((x,y)->Double.compare(c.distanceToSqr(x),c.distanceToSqr(y))).orElse(null);
        if(v==null)return "FAILED no loaded villager within radius="+radius;
        StringBuilder b=new StringBuilder("SUCCESS villager=").append(v.getName().getString()).append(" profession=").append(BuiltInRegistries.VILLAGER_PROFESSION.getKey(v.getVillagerData().getProfession())).append(" offers=");
        int i=0; for(MerchantOffer o:v.getOffers()){
            if(i++>=32)break;
            ItemStack ca=o.getCostA(), cb=o.getCostB(), r=o.getResult();
            b.append("[").append(i-1).append(" costA=").append(desc(ca)).append(" costB=").append(desc(cb)).append(" result=").append(desc(r)).append(" stock=").append(o.getMaxUses()-o.getUses()).append(" demand=").append(o.getDemand()).append(" affordable=").append(canPay(c.getCompanionInventory(),ca,cb)).append("]");
        }
        return b.toString();
    }
    static boolean canPay(net.minecraft.world.Container inv, ItemStack a, ItemStack b){
        return count(inv,a)>=a.getCount() && (b.isEmpty() || count(inv,b)>=b.getCount());
    }
    static int count(net.minecraft.world.Container inv, ItemStack wanted){int n=0;for(int i=0;i<inv.getContainerSize();i++){ItemStack s=inv.getItem(i);if(!s.isEmpty()&&ItemStack.isSameItemSameTags(s,wanted))n+=s.getCount();}return n;}
    static String desc(ItemStack s){return s.isEmpty()?"none":s.getHoverName().getString()+"x"+s.getCount();}
}
