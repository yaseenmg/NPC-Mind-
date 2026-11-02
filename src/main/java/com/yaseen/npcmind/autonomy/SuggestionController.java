package com.yaseen.npcmind.autonomy;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;

/** Produces evidence-backed suggestions, throttled so the companion does not spam the player. */
@Internal
public final class SuggestionController {
    public static void tick(CompanionEntity c){
        if(!c.getAutonomy().suggestions()||c.level().isClientSide()||c.tickCount%200!=0)return;
        if(!(c.getOwnerPlayer() instanceof ServerPlayer p))return;
        long now=p.serverLevel().getGameTime();String msg=null;
        if(c.getGoal().active()&&c.getAutonomy().lastRoutineTime()!=Long.MIN_VALUE&&now-c.getAutonomy().lastRoutineTime()>1200)msg="We've been idle a while. Want me to do something?";
        if(msg!=null){c.say(msg);c.getCompanionState().positiveEvent();}
    }
    private SuggestionController(){}
}
