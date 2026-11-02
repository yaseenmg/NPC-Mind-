package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.autonomy.NavigationArbiter;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.task.TaskManager;
import com.yaseen.npcmind.state.SkillProgression;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Monster;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.yaseen.npcmind.api.Internal;

/** Owner rescue behavior based only on live health/threat/inventory. */
@Internal
public final class EmergencyController {
    private static final Map<UUID,Long> LAST=new ConcurrentHashMap<>();
    public static void tick(CompanionEntity c){
        if(c.level().isClientSide()||c.tickCount%10!=0)return;
        if(!(c.getOwnerPlayer() instanceof ServerPlayer p))return;
        if(c.getTarget()!=null && c.getTarget().isAlive())return;
        if(p.getHealth()>p.getMaxHealth()*.35)return;
        var target=p.serverLevel().getEntitiesOfClass(Monster.class,p.getBoundingBox().inflate(10),e->e.isAlive() && e.getTarget()==p)
                .stream().min((a,b)->Double.compare(p.distanceToSqr(a),p.distanceToSqr(b))).orElse(null);
        if(target==null)return;
        // BUG FIX: `now - Long.MIN_VALUE` overflows to a negative value, so the cooldown test was
        // always true and the emergency rescue NEVER fired. Use a null sentinel.
        long now=p.serverLevel().getGameTime();
        Long last=LAST.get(p.getUUID());
        if(last!=null && now-last<100)return;
        LAST.put(p.getUUID(),now);
        c.getCompanionState().dangerEvent(); c.getWorldMemory().failure("owner emergency: hp="+p.getHealth()+" attacking_threat="+target.getType().builtInRegistryHolder().key().location());
        TaskManager.cancelCurrentTask(c); c.setTarget(target); NavigationArbiter.request(c, target, "emergency", 900, 1.25);
        c.say("Your health is low — I'm coming to help!");
        c.getSkills().add(SkillProgression.Skill.SURVIVAL,2);
    }
    public static void clear(UUID id){LAST.remove(id);}
    private EmergencyController(){}
}
