package com.yaseen.npcmind.autonomy;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import java.util.*;
import com.yaseen.npcmind.api.Internal;

/** Detects real loaded block entities that expose Forge Energy and reports low-energy systems. */
@Internal
public final class MaintenanceController {
    public static String scan(CompanionEntity c,int radius){
        if(!(c.level() instanceof ServerLevel l))return "ERROR not_server";radius=Math.max(2,Math.min(32,radius));int total=0,low=0;StringBuilder b=new StringBuilder();
        // The old 65³ triple loop called getBlockEntity() ~274k times in one tick AND could
        // synchronously load chunks. Iterate already-loaded block entities once instead and
        // filter by distance - same result, no chunk loading, no tick spike.
        BlockPos center=c.blockPosition();double radiusSq=(double)radius*radius;
        for(BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))){
            if(!l.hasChunkAt(pos)) continue;
            BlockEntity be=l.getBlockEntity(pos);
            if(be==null||be.isRemoved())continue;
            var p=be.getBlockPos();
            if(p.distSqr(center)>radiusSq)continue;
            var cap=be.getCapability(ForgeCapabilities.ENERGY);if(!cap.isPresent())continue;IEnergyStorage e=cap.orElse(null);if(e==null)continue;total++;int max=e.getMaxEnergyStored();int now=e.getEnergyStored();if(max>0&&now*100L/max<15){low++;b.append(be.getBlockPos()).append("=").append(now).append('/').append(max).append(';');}
        }
        return "RESULT energy_nodes="+total+" low_energy="+low+" details="+b;
    }
    private MaintenanceController(){}
}
