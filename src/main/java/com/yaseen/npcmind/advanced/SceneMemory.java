package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;
import com.yaseen.npcmind.api.Internal;

/** Spatial scene snapshots made from real loaded blocks/entities; no screenshots or invented geometry. */
@Internal
public final class SceneMemory {
    public static void tick(CompanionEntity c){
        if(c.level().isClientSide()||c.tickCount%100!=0||!(c.level() instanceof ServerLevel l))return;
        BlockPos base=c.blockPosition();Map<String,Integer> counts=new HashMap<>();
        for(int dx=-4;dx<=4;dx++)for(int dy=-2;dy<=3;dy++)for(int dz=-4;dz<=4;dz++){
            BlockState s=l.getBlockState(base.offset(dx,dy,dz));String id=ForgeRegistries.BLOCKS.getKey(s.getBlock())==null?"unknown":ForgeRegistries.BLOCKS.getKey(s.getBlock()).toString();counts.put(id,counts.getOrDefault(id,0)+1);
        }
        List<Map.Entry<String,Integer>> top=new ArrayList<>(counts.entrySet());top.sort((a,b)->Integer.compare(b.getValue(),a.getValue()));StringBuilder note=new StringBuilder("scene blocks=");for(int i=0;i<Math.min(8,top.size());i++)note.append(top.get(i).getKey()).append(':').append(top.get(i).getValue()).append(',');
        int entities=l.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,c.getBoundingBox().inflate(10),e->e.isAlive()).size();note.append("living_entities=").append(entities);
        c.getWorldMemory().observe("scene_snapshot",l.dimension().location().toString(),base,note.toString(),l.getGameTime());
    }
    private SceneMemory(){}
}
