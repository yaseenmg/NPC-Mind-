package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.security.WorldActionSecurity;
import com.yaseen.npcmind.state.SkillProgression;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.crafting.InventoryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import com.yaseen.npcmind.api.Internal;

@Internal
public final class TradeWithVillagerTool implements AITool {
    public String name(){return "trade_with_villager";}
    public String description(){return "Execute one real available Villager trade using the companion inventory. Choose an offer index returned by inspect_villager_trades; no price is invented or changed.";}
    public String schema(){return "{\"offer\":number}";}
    public String execute(CompanionEntity c, ServerPlayer p, String a){
        if(!c.getPermissions().allowed(ActionPermissions.Action.TRADE)) return "DENIED permission=trade";
        Integer idx=MoveToTool.num(a,"offer"); if(idx==null)return "FAILED offer index required";
        if (!(c.level() instanceof ServerLevel level)) return "FAILED trade requires server level";
        Villager v=level.getEntitiesOfClass(Villager.class,c.getBoundingBox().inflate(16),x->x.isAlive()).stream().min((x,y)->Double.compare(c.distanceToSqr(x),c.distanceToSqr(y))).orElse(null);
        if(v==null)return "FAILED no villager within 16 blocks";
        if(!WorldActionSecurity.canInteractWithEntity(c,p,v)) return "DENIED companion must be within 8 blocks of the villager and owner permission must allow trading";
        if(idx<0||idx>=v.getOffers().size())return "FAILED invalid offer index";
        MerchantOffer o=v.getOffers().get(idx);
        ItemStack ca=o.getCostA(), cb=o.getCostB(), result=o.getResult().copy();
        if(o.isOutOfStock())return "FAILED offer out_of_stock";
        Container inv=c.getCompanionInventory();
        if(!InspectVillagerTradesTool.canPay(inv,ca,cb))return "FAILED companion cannot afford real costs";
        Container sim=new net.minecraft.world.SimpleContainer(inv.getContainerSize());for(int i=0;i<inv.getContainerSize();i++)sim.setItem(i,inv.getItem(i).copy());
        if(!remove(sim,ca)||(!cb.isEmpty()&&!remove(sim,cb))||!InventoryOps.add(sim,result.copy()))return "FAILED inventory simulation rejected trade";
        if(!remove(inv,ca)||(!cb.isEmpty()&&!remove(inv,cb))){return "FAILED payment mutation was incomplete";}
        if(!InventoryOps.add(inv,result.copy()))return "FAILED result insertion failed after payment";
        o.increaseUses();
        v.notifyTrade(o);
        c.getSkills().add(SkillProgression.Skill.CRAFTING,5); c.getCompanionState().positiveEvent();
        return "SUCCESS traded_with="+v.getName().getString()+" offer="+idx+" result="+result.getHoverName().getString()+" x"+result.getCount();
    }
    private static boolean remove(Container inv,ItemStack wanted){int need=wanted.getCount();for(int i=0;i<inv.getContainerSize()&&need>0;i++){ItemStack s=inv.getItem(i);if(!s.isEmpty()&&ItemStack.isSameItemSameTags(s,wanted)){int n=Math.min(need,s.getCount());s.shrink(n);need-=n;if(s.isEmpty())inv.setItem(i,ItemStack.EMPTY);}}return need==0;}
}
