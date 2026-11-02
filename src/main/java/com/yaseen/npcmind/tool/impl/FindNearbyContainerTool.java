package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.tool.AITool;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import com.yaseen.npcmind.api.Internal;

@Internal
public final class FindNearbyContainerTool implements AITool {
    public String name(){return "find_nearby_container";}
    public String description(){return "Search the live loaded world near the companion for actual Container block entities.";}
    public String schema(){return "{\"radius\":number(optional)}";}
    public String execute(CompanionEntity c, ServerPlayer p, String a){
        Integer radius=MoveToTool.num(a,"radius"); if(radius==null)radius=8; radius=Math.max(1,Math.min(radius,16));
        BlockPos base=c.blockPosition(); StringBuilder b=new StringBuilder("REAL_CONTAINERS\n"); int found=0;
        for(BlockPos pos:BlockPos.betweenClosed(base.offset(-radius,-radius,-radius),base.offset(radius,radius,radius))){
            if (!c.level().isLoaded(pos)) continue;
            if(c.level().getBlockEntity(pos) instanceof Container
                    && com.yaseen.npcmind.security.WorldActionSecurity.canAccessContainer(c,p,pos)){
                b.append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ()).append(' ').append(c.level().getBlockState(pos).getBlock().getDescriptionId()).append('\n');
                if(++found>=32)break;
            }
        }
        return found==0?"NO_REAL_CONTAINERS_FOUND":b.toString();
    }
}
