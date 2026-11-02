package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.agent.InternalToolExecutor;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import com.yaseen.npcmind.api.Internal;


/**
 * Coordinate-free cooking. The companion finds the nearest real furnace / smoker / blast-furnace
 * around it and delegates the actual cooking to {@link SmeltItemTool} (which loads real input +
 * fuel and lets Minecraft tick the burn). The local command router can handle simple "cook" / "cook food" requests.
 */
@Internal
public final class CookFoodTool implements AITool {
    public String name(){ return "cook_food"; }
    public String description(){ return "Cook/smelt using the NEAREST real furnace, smoker or blast furnace around the companion — no coordinates needed. Optionally give an output keyword (e.g. steak, cooked_porkchop, iron_ingot); otherwise it cooks the first thing it has a recipe and fuel for. Prefers a smoker for food."; }
    public String schema(){ return "{\"output\":\"keyword(optional)\"}"; }

    public String execute(CompanionEntity c, ServerPlayer p, String a){
        if(!c.getPermissions().allowed(ActionPermissions.Action.CRAFT)) return "DENIED permission=craft";
        if(!(c.level() instanceof ServerLevel level)) return "ERROR not_server";
        String query = JsonArgs.string(a, "output");
        if(query == null) query = "";

        BlockPos station = findStation(level, c.blockPosition());
        if(station == null) return "FAILED no_furnace_or_smoker_nearby";
        String blockId = level.getBlockState(station).getBlock().builtInRegistryHolder().key().location().toString();
        String mode = switch(blockId){
            case "minecraft:smoker" -> "smoking";
            case "minecraft:blast_furnace" -> "blasting";
            default -> "smelting";
        };
        String requestedOutput = query.isBlank() ? "food" : query;
        String args = "{\"type\":\"" + mode + "\",\"output\":\"" + requestedOutput + "\",\"x\":" + station.getX()
                + ",\"y\":" + station.getY() + ",\"z\":" + station.getZ() + "}";
        return InternalToolExecutor.invoke(c, p, "smelt_item", args, "internal-" + java.util.UUID.randomUUID()).result();
    }

    /** Nearest smoker/furnace/blast furnace within 8 blocks; smokers win ties so food cooks faster. */
    private static BlockPos findStation(ServerLevel level, BlockPos base){
        BlockPos best = null; double bestScore = Double.MAX_VALUE;
        for(BlockPos pos : BlockPos.betweenClosed(base.offset(-8,-4,-8), base.offset(8,4,8))){
            if(!level.isLoaded(pos)) continue;
            BlockState st = level.getBlockState(pos);
            String id = st.getBlock().builtInRegistryHolder().key().location().toString();
            double rank;
            switch(id){
                case "minecraft:smoker" -> rank = 0.0;
                case "minecraft:furnace" -> rank = 1.0;
                case "minecraft:blast_furnace" -> rank = 2.0;
                default -> { continue; }
            }
            double score = base.distSqr(pos) + rank * 0.001;
            if(score < bestScore){ bestScore = score; best = pos.immutable(); }
        }
        return best;
    }
}
