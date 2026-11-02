package com.yaseen.npcmind.autonomy;

import com.yaseen.npcmind.agent.AgentEngine;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.advanced.ExplorationController;
import com.yaseen.npcmind.advanced.BodyguardController;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.yaseen.npcmind.api.Internal;

/** Local autonomous scheduler. It uses the deterministic action router and vanilla controllers only. */
@Internal
public final class AutonomyController {
    private static final Map<UUID,Long> LAST=new ConcurrentHashMap<>();
    private static final long THINK_INTERVAL=600L;
    public static void tick(CompanionEntity c){
        if(c.level().isClientSide()||!c.getAutonomy().routine())return;
        // Combat has absolute priority over autonomous actions. The vanilla goal system
        // also uses a target as the source of move/attack behavior, so never enqueue a
        // second autonomous decision while that target is alive.
        if(c.getTarget() != null && c.getTarget().isAlive()) return;
        if(!(c.getOwnerPlayer() instanceof ServerPlayer p))return;
        if(AgentEngine.isBusy(c.getUUID()))return;
        long now=p.serverLevel().getGameTime();
        // Autonomous work must remain local. The deterministic action router gets first refusal;
        // no model/API fallback is attempted when it has no safe action.
        if (AutonomyActionRouter.tick(c, p)) return;
        // BUG FIX: the old code used Long.MIN_VALUE as the "never thought yet" sentinel and then
        // computed `now - Long.MIN_VALUE`, which overflows to a large negative number. The
        // comparison was therefore always true and autonomous local decision cycle never ran at all -
        // LAST was never even written. Use an explicit null check instead.
        Long last = LAST.get(c.getUUID());
        if (last != null && now - last < THINK_INTERVAL) return;
        LAST.put(c.getUUID(),now);
        // Mode-specific low-level controllers act continuously. Only enable controllers from the
        // selected mode; never switch off a controller the player enabled manually.
        switch(c.getAutonomy().mode()){
            case EXPLORER -> ExplorationController.set(c,true);
            case BODYGUARD -> BodyguardController.set(c,true);
            default -> { }
        }
        c.getAutonomy().routineDone("no_safe_local_autonomous_action", now);
    }
    public static void clear(UUID owner){LAST.remove(owner); AutonomyActionRouter.clear(owner);}
    private AutonomyController(){}
}
