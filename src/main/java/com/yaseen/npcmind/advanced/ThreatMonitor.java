package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.yaseen.npcmind.api.Internal;

/** Server-side early-warning system based only on real loaded entities/environment. */
@Internal
public final class ThreatMonitor {
    private static final Map<UUID,Long> LAST_WARNING=new ConcurrentHashMap<>();
    public static void tick(ServerPlayer p, CompanionEntity c){
        if(p==null||c==null||!c.isAlive()||p.level()!=c.level())return;
        // The kernel calls this every tick; scanning an 18-block monster box each tick is wasted work.
        if(p.serverLevel().getGameTime()%20L!=0L)return;
        Monster threat=null; double best=Double.MAX_VALUE;
        for(Monster m:p.serverLevel().getEntitiesOfClass(Monster.class,p.getBoundingBox().inflate(18),x->x.isAlive())){
            double d=p.distanceToSqr(m); if(d>=best)continue;
            double dot=(m.getX()-p.getX())*p.getLookAngle().x+(m.getZ()-p.getZ())*p.getLookAngle().z;
            boolean behind=dot<0.0;
            if(m instanceof Creeper && behind && d<18*18){threat=m;best=d;}
        }
        if(threat==null)return;
        // BUG FIX: `now - Long.MIN_VALUE` overflows to a negative number, so this cooldown was
        // always active and the "creeper behind you" warning never fired.
        long now=p.serverLevel().getGameTime(); Long last=LAST_WARNING.get(p.getUUID());
        if(last!=null && now-last<200)return;
        LAST_WARNING.put(p.getUUID(),now);
        String msg="Creeper behind you! ("+String.format(java.util.Locale.ROOT,"%.1f",Math.sqrt(best))+"m)";
        p.sendSystemMessage(net.minecraft.network.chat.Component.literal("<"+"AI Companion"+"> "+msg));
        c.getCompanionState().dangerEvent();
    }
    public static void clear(UUID id){if(id!=null)LAST_WARNING.remove(id);}
    private ThreatMonitor(){}
}
