package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.autonomy.NavigationArbiter;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.task.TaskManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.yaseen.npcmind.api.Internal;

/** Real follow/protection behavior. It never targets the owner. */
@Internal
public final class BodyguardController {
    private static final Map<UUID,Boolean> ENABLED=new ConcurrentHashMap<>();
    public static void set(CompanionEntity c,boolean v){ENABLED.put(c.getUUID(),v);}
    public static boolean enabled(CompanionEntity c){return ENABLED.getOrDefault(c.getUUID(),false);}
    public static void tick(CompanionEntity c){
        if(c.level().isClientSide()||c.tickCount%10!=0||!enabled(c))return;
        if(!(c.getOwnerPlayer() instanceof ServerPlayer p))return;
        if(c.getTarget()!=null && c.getTarget().isAlive()) return;
        // Bodyguard mode only reacts to an actual attack on the owner. A nearby
        // monster that is doing nothing is NOT a valid target.
        LivingEntity target=null; double best=Double.MAX_VALUE;
        for(LivingEntity e:c.level().getEntitiesOfClass(LivingEntity.class,p.getBoundingBox().inflate(12),x->x.isAlive()&&x!=p&&x!=c)){
            if(!(e instanceof Monster)) continue;
            // A ranged mob
            // (skeleton/witch) may already have cleared its vanilla target after landing the
            // hit, so getTarget()==p alone misses the actual attacker. The owner's
            // getLastHurtByMob() within the last 5 seconds catches exactly that case.
            boolean attackingOwner = (e instanceof net.minecraft.world.entity.Mob mob && mob.getTarget()==p)
                    || (p.getLastHurtByMob()==e && p.tickCount-p.getLastHurtByMobTimestamp()<=100);
            if(!attackingOwner) continue;
            double d=p.distanceToSqr(e);
            if(d<best){best=d;target=e;}
        }
        if(target!=null){TaskManager.cancelCurrentTask(c);c.setTarget(target);NavigationArbiter.request(c, target, "bodyguard", 700, 1.25);}
        else if(p.getHealth()<=p.getMaxHealth()*.35 && c.distanceToSqr(p)>25){NavigationArbiter.request(c, p, "bodyguard", 700, 1.35);}
        else if(c.distanceToSqr(p)>36 && c.isFollowEnabled()){NavigationArbiter.request(c, p, "follow", 300, 1.25);}
    }
    public static void clear(UUID id){ENABLED.remove(id);}
    private BodyguardController(){}
}
