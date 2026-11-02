package com.yaseen.npcmind.autonomy;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import com.yaseen.npcmind.api.Internal;

/** Conservative real-world navigation helper; it uses vanilla navigation and records successful destinations. */
@Internal
public final class NavigationController {
    public static String move(CompanionEntity c, BlockPos target){
        if(!(c.level() instanceof ServerLevel l))return "ERROR not_server";
        if(target==null)return "ERROR missing_target";
        if(!l.hasChunkAt(target))return "FAILED target_chunk_not_loaded";
        double d=c.blockPosition().distSqr(target);if(d>256*256)return "FAILED target_too_far";
        var state = l.getBlockState(target);
        if(!state.getFluidState().isEmpty() || state.is(Blocks.LAVA)||state.is(Blocks.FIRE))return "DENIED hazardous_target";
        boolean ok=NavigationArbiter.request(c,target,"navigation",300,1.15);
        if(ok)c.getWorldMemory().observe("navigation_target",l.dimension().location().toString(),target,"vanilla navigation target",l.getGameTime());
        return ok?"STARTED navigation target="+target:"FAILED navigation_rejected";
    }
    public static boolean arrived(CompanionEntity c,BlockPos p){return p!=null&&c.blockPosition().distSqr(p)<=4;}
    private NavigationController(){}
}
