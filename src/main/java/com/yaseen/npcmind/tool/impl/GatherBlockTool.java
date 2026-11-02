package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.task.TaskManager;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import com.yaseen.npcmind.api.Internal;

/**
 * Generic deterministic block breaker. Unlike {@link MineBlockTool} it does NOT need absolute
 * coordinates (natural-language commands often omit exact x/y/z coordinates): it scans the real loaded
 * blocks around the companion, keeps the ones whose registry id contains the requested keyword,
 * and queues verified mining tasks for the nearest matches. This is what makes "break <anything>"
 * work, not only wood.
 */
@Internal
public final class GatherBlockTool implements AITool {
    public String name(){ return "gather_block"; }
    public String description(){ return "Find nearby real loaded blocks whose id matches a keyword (e.g. dirt, stone, sand, gravel, glass, cobblestone, clay, wool) and mine several of them. No coordinates needed."; }
    public String schema(){ return "{\"block\":string,\"count\":number(optional)}"; }

    public String execute(CompanionEntity c, ServerPlayer p, String a){
        if(!c.getPermissions().allowed(ActionPermissions.Action.MINE)) return "DENIED permission=mine";
        if(!(c.level() instanceof ServerLevel level)) return "ERROR not_server";
        String keyword = JsonArgs.string(a, "block");
        if(keyword == null || keyword.isBlank()) return "FAILED block keyword is required";
        keyword = keyword.toLowerCase(Locale.ROOT).trim();
        Integer req = JsonArgs.integer(a, "count");
        int count = req == null ? 6 : Math.max(1, Math.min(16, req));

        List<BlockPos> hits = new ArrayList<>();
        List<BlockPos> hiddenHits = new ArrayList<>();
        BlockPos base = c.blockPosition();
        for(int dx=-8; dx<=8; dx++) for(int dy=-4; dy<=4; dy++) for(int dz=-8; dz<=8; dz++){
            if(Math.abs(dx)+Math.abs(dz) > 12) continue;
            BlockPos pos = base.offset(dx,dy,dz);
            if(!level.hasChunkAt(pos)) continue;
            BlockState st = level.getBlockState(pos);
            if(!matches(st, keyword)) continue;
            // Prefer exposed blocks, but retain a secondary list of covered targets. MineTask has
            // natural-access-path logic; filtering all covered ores here made buried iron/coal
            // impossible to gather even when a safe short access tunnel existed.
            if (isExposed(level, pos)) hits.add(pos.immutable());
            else hiddenHits.add(pos.immutable());
        }
        Comparator<BlockPos> distanceOrder = Comparator.comparingDouble(pos -> c.distanceToSqr(pos.getX()+0.5, pos.getY()+0.5, pos.getZ()+0.5));
        hits.sort(distanceOrder);
        hiddenHits.sort(distanceOrder);
        hits.addAll(hiddenHits); // known-reachable surfaces first, access-path targets second
        if(hits.isEmpty()) return "FAILED no_loaded_block_nearby=" + keyword;
        TaskManager.cancelCurrentTask(c);
        int queued = 0;
        int consecutiveFailures = 0;
        for(BlockPos pos : hits){
            if(queued >= count) break;
            String r = TaskManager.mine(c, pos);
            // Skip a failing candidate and try the next one: hiddenHits sit behind the open
            // surfaces in this list, so a break on the first refused mine (protection, fluid,
            // respawn anchor) abandoned every remaining reachable ore. A bounded consecutive
            // failure budget (3) still prevents long loops over a fully protected vein.
            if(r != null && r.startsWith("STARTED")) { queued++; consecutiveFailures = 0; }
            else if(++consecutiveFailures >= 3) break;
        }
        return queued > 0 ? "STARTED gather_block block=" + keyword + " queued=" + queued : "FAILED could_not_queue=" + keyword;
    }

    /** A block is a candidate when it is breakable and its registry id (or a synonym) contains the keyword. */
    private static boolean matches(BlockState st, String keyword){
        if(st.isAir()) return false;
        if(!st.getFluidState().isEmpty()) return false;         // skip water/lava source/flow
        if(st.is(Blocks.BEDROCK)) return false;                  // unbreakable in survival
        ResourceLocation id = st.getBlock().builtInRegistryHolder().key().location();
        String path = id.getPath().toLowerCase(Locale.ROOT);     // e.g. "dirt", "cobblestone", "oak_log"
        // Exact mode ("=stone"): the registry path must equal the keyword. Used by the quest so
        // "stone" never matches stone_bricks / stonecutter / stone_slab built by the player.
        if(keyword.startsWith("=")) return path.equals(keyword.substring(1));
        // Never destroy things players build or use (doors, chests, furnaces, bricks, slabs, blocks of ore...).
        // Natural "grass_block" is the one "block" we do want for the dirt family.
        if(isPlayerMade(path) && !keyword.contains(denyHit(path))) return false;
        // Handle ambiguous families before generic substring matching: "stone" must never
        // match redstone_ore, and should not select unrelated modded names such as limestone.
        if (keyword.equals("stone")) {
            return path.equals("stone") || path.equals("deepslate") || path.equals("granite")
                    || path.equals("diorite") || path.equals("andesite") || path.equals("tuff")
                    || path.equals("end_stone") || path.equals("blackstone") || path.equals("basalt");
        }
        if(path.equals(keyword) || path.contains(keyword)) return true;
        // A couple of forgiving aliases so a single keyword catches the obvious family.
        switch(keyword){
            case "stone":  return false;
            case "dirt":   return path.equals("dirt") || path.equals("coarse_dirt") || path.equals("rooted_dirt") || path.equals("grass_block") || path.equals("podzol") || path.equals("mycelium");
            case "sand":   return path.contains("sand");          // sand, red_sand, sandstone
            case "wood":
            case "log":    return path.contains("log") || path.contains("wood") || path.contains("stem");
            case "glass":  return path.contains("glass");
            case "wool":   return path.contains("wool");
            case "leaves": return path.contains("leaves");
            case "ice":    return path.contains("ice");
            case "ore":    return path.contains("ore");
            default:       return false;
        }
    }

    private static final String[] PLAYER_MADE = {"brick", "slab", "stair", "wall", "button", "plate", "cutter", "door",
            "bars", "fence", "gate", "chest", "barrel", "furnace", "smoker", "blast", "crafting", "table", "bed",
            "sign", "ladder", "lantern", "torch", "rail", "banner", "carpet", "shelf", "anvil", "enchant", "beacon",
            "hopper", "dropper", "dispenser", "piston", "lever", "frame", "pot", "block"};

    private static boolean isPlayerMade(String path){
        if(path.equals("grass_block")) return false;
        return !denyHit(path).isEmpty();
    }

    /** The first player-made marker found in the path, or "" when the block looks natural. */
    private static String denyHit(String path){
        for(String d : PLAYER_MADE) if(path.contains(d)) return d;
        return "";
    }

    /** True when at least one of the 6 faces touches a non-solid block (air, cave, tunnel, surface). */
    private static boolean isExposed(ServerLevel level, BlockPos pos){
        for(net.minecraft.core.Direction d : net.minecraft.core.Direction.values()){
            BlockPos n = pos.relative(d);
            if(!level.hasChunkAt(n)) continue;
            BlockState ns = level.getBlockState(n);
            if(ns.isAir() || ns.getCollisionShape(level, n).isEmpty()) return true;
        }
        return false;
    }
}
