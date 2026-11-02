package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.autonomy.NavigationArbiter;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.state.SkillProgression;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.yaseen.npcmind.api.Internal;

/** Chooses real nearby positions whose chunks have not been recorded as visited. */
@Internal
public final class ExplorationController {
    private static final Map<UUID,Boolean> ACTIVE=new ConcurrentHashMap<>();
    /** Per-companion chunk keys picked most recently, so a blocked candidate rotates out
     * instead of being re-requested forever (the controller gets no navigation feedback). */
    private static final Map<UUID,Deque<String>> RECENT_PICKS=new ConcurrentHashMap<>();
    private static final int RECENT_KEEP=3;
    public static void set(CompanionEntity c,boolean v){ACTIVE.put(c.getUUID(),v);}
    public static boolean active(CompanionEntity c){return ACTIVE.getOrDefault(c.getUUID(),false);}
    public static void tick(CompanionEntity c){
        if(!active(c)||c.level().isClientSide()||c.tickCount%100!=0||!(c.level() instanceof ServerLevel l))return;
        BlockPos base=c.blockPosition(); BlockPos best=null; double score=-1; String bestKey=null;
        Deque<String> recent=RECENT_PICKS.computeIfAbsent(c.getUUID(),k->new ArrayDeque<>());
        for(int dx=-8;dx<=8;dx++)for(int dz=-8;dz<=8;dz++){if(Math.abs(dx)+Math.abs(dz)<4)continue;int x=base.getX()+dx*16,z=base.getZ()+dz*16;
            // getHeightmapPos on an unloaded column returns bedrock height, which poisoned the
            // score with fake y=-64 candidates. Skip columns whose chunks are not loaded; the
            // controller never force-loads terrain.
            if(!l.hasChunkAt(new BlockPos(x,0,z)))continue;
            String chunkKey=l.dimension().location()+":"+(x>>4)+":"+(z>>4);
            if(recent.contains(chunkKey))continue;
            BlockPos p=l.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,new BlockPos(x,0,z));if(c.getWorldMemory().visited(l.dimension().location().toString(),p))continue;
            // Weighted jitter avoids deterministic selection of the same blocked candidate.
            // The old score was fully deterministic, so the
            // nearest unvisited chunk always won — even when navigation to it kept failing,
            // producing an infinite retry loop on the same blocked target. Weighted jitter
            // (0.75x..1.25x) keeps near targets favored while letting a stuck candidate
            // rotate out and exploration spread naturally.
            double s=(1.0/(1.0+p.distSqr(base)))*(0.75D+0.5D*l.random.nextDouble());
            if(s>score){score=s;best=p;bestKey=chunkKey;}}
        if(best!=null){
            recent.addLast(bestKey); if(recent.size()>RECENT_KEEP)recent.removeFirst();
            c.getWorldMemory().observe("exploration_target",l.dimension().location().toString(),best,"selected real unvisited chunk",l.getGameTime());NavigationArbiter.request(c, best, "exploration", 200, 1.15);c.getSkills().add(SkillProgression.Skill.EXPLORATION,1);}
    }
    public static void clear(UUID id){ACTIVE.remove(id);RECENT_PICKS.remove(id);}
    private ExplorationController(){}
}
