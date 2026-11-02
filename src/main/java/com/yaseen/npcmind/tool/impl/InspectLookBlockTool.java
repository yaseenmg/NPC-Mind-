package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.knowledge.ModpackKnowledge;
import com.yaseen.npcmind.tool.AITool;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import com.yaseen.npcmind.api.Internal;

@Internal
public final class InspectLookBlockTool implements AITool {
    public String name() { return "inspect_look_block"; }
    public String description() { return "Read the exact block the owner is currently looking at using a real ray trace."; }
    public String schema() { return "{\"distance\":number(optional)}"; }
    public String execute(CompanionEntity companion, ServerPlayer player, String arguments) {
        double distance = InspectDistance(arguments);
        HitResult hit = player.pick(distance, 0.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) return "RESULT no_block_hit=true";
        BlockHitResult block = (BlockHitResult) hit;
        return "RESULT block=" + ModpackKnowledge.blockInfo(player.serverLevel().getBlockState(block.getBlockPos()))
            + " pos=" + block.getBlockPos();
    }
    private double InspectDistance(String json) {
        int n = InspectNearbyEntitiesTool.number(json, "distance", 6);
        return Math.max(1, Math.min(n, 32));
    }
}
