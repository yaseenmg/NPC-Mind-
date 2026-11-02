package com.yaseen.npcmind.autonomy;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import com.yaseen.npcmind.api.Internal;

/** Optional low-impact autonomous routine. It never mutates the world unless an existing safe action is already available. */
@Internal
public final class DailyRoutineController {
    public static void tick(CompanionEntity c){
        if(!c.getAutonomy().routine()||c.level().isClientSide()||c.tickCount%200!=0)return;
        if(!(c.getOwnerPlayer() instanceof ServerPlayer p))return;
        long now=p.serverLevel().getGameTime();
        // BUG FIX: lastRoutineTime starts at Long.MIN_VALUE; `now - Long.MIN_VALUE` overflows to a
        // negative value, so this guard always returned and the daily routine never ran.
        long last=c.getAutonomy().lastRoutineTime();
        if(last!=Long.MIN_VALUE && now-last<600)return;
        String action="inspect_and_prepare";
        if(c.getCompanionInventory().countItem(Items.TORCH)<8)action="inventory_check";
        else if(c.getGoal().active())action="resume_goal";
        else action="base_check";
        c.getAutonomy().routineDone(action,now);
        c.getWorldMemory().observe("daily_routine",p.serverLevel().dimension().location().toString(),c.blockPosition(),action,now);
    }
    private DailyRoutineController(){}
}
