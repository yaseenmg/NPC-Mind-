package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.yaseen.npcmind.api.Internal;

/**
 * Coordinate-free block placing. Natural-language commands often omit x/y/z coordinates, so this
 * tool just needs a block KEYWORD: it finds a matching real {@link BlockItem} in the companion
 * inventory and places up to {@code count} copies on valid ground positions in front of the
 * companion. This is what makes "place a block here" work for any block the player owns.
 */
@Internal
public final class PlaceBlockTool implements AITool {
    public String name(){ return "place_block"; }
    public String description(){ return "Place blocks from the companion's own inventory WITHOUT coordinates. Give a block keyword (e.g. stone, dirt, cobblestone, planks, torch, chest) and an optional count; the companion places that many on valid ground spots in front of it, consuming the real items."; }
    public String schema(){ return "{\"block\":string(optional),\"count\":number(optional),\"x\":number(optional),\"y\":number(optional),\"z\":number(optional)}"; }

    public String execute(CompanionEntity c, ServerPlayer p, String a){
        if(!c.getPermissions().allowed(ActionPermissions.Action.BUILD)) return "DENIED permission=build";
        if (!(c.getOwnerPlayer() instanceof ServerPlayer owner)) return "DENIED owner is not online; world mutation is blocked";
        String keyword = JsonArgs.string(a, "block");
        keyword = keyword == null ? "" : keyword.toLowerCase(Locale.ROOT).trim();
        if (keyword.isBlank()) keyword = mostAbundantBlockKeyword(c);
        if (keyword == null || keyword.isBlank()) return "FAILED no_placeable_block_in_inventory";
        Integer req = JsonArgs.integer(a, "count");
        int count = req == null ? 1 : Math.max(1, Math.min(64, req));

        int slot = findBlockSlot(c, keyword);
        if(slot < -2 || slot == -1) return "FAILED no_block_in_inventory=" + keyword;
        ItemStack selectedStack = slot == -2 ? c.getMainHandItem() : c.getCompanionInventory().getItem(slot);
        if (selectedStack.isEmpty()) return "FAILED no_block_in_inventory=" + keyword;
        Block block = Block.byItem(selectedStack.getItem());
        if(block == null || block == Blocks.AIR) return "FAILED not_a_placeable_block=" + keyword;

        Integer x = JsonArgs.integer(a, "x"), y = JsonArgs.integer(a, "y"), z = JsonArgs.integer(a, "z");
        List<BlockPos> targets;
        if (x != null || y != null || z != null) {
            if (x == null || y == null || z == null) return "FAILED coordinates_require_x_y_z";
            BlockPos first = new BlockPos(x, y, z);
            if (!c.level().isInWorldBounds(first) || !c.level().getWorldBorder().isWithinBounds(first)) return "FAILED target_outside_world_bounds";
            if (!c.level().hasChunkAt(first)) return "FAILED target_chunk_not_loaded";
            if (c.blockPosition().distSqr(first) > 128.0D * 128.0D) return "FAILED target_too_far navigate_there_first";
            targets = new ArrayList<>();
            Direction forward = c.getDirection();
            for (int i = 0; i < count; i++) targets.add(first.relative(forward, i));
        } else {
            List<BlockPos> spots = candidateSpots(c);
            if (spots.isEmpty()) return "FAILED no_loaded_build_spots_nearby";
            int requested = Math.min(count, spots.size());
            targets = new ArrayList<>(spots.subList(0, requested));
        }
        // Use TaskManager for every placement path. It walks into reach, re-validates each cell,
        // checks protection and line of sight, consumes real inventory items and paces the build.
        String blockId = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block) == null
                ? keyword : net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block).toString();
        return com.yaseen.npcmind.task.TaskManager.build(c, targets, block, blockId);

    }


    private static String mostAbundantBlockKeyword(CompanionEntity c) {
        String best = null;
        int bestCount = 0;
        ItemStack held = c.getMainHandItem();
        if (!held.isEmpty() && held.getItem() instanceof BlockItem) {
            Block heldBlock = Block.byItem(held.getItem());
            var heldId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(held.getItem());
            if (heldBlock != null && heldBlock != Blocks.AIR && heldBlock.defaultBlockState().blocksMotion() && heldId != null) {
                best = heldId.getPath();
                bestCount = held.getCount();
            }
        }
        for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
            ItemStack stack = c.getCompanionInventory().getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) continue;
            Block candidateBlock = Block.byItem(stack.getItem());
            if (candidateBlock == null || candidateBlock == Blocks.AIR || !candidateBlock.defaultBlockState().blocksMotion()) continue;
            if (stack.getCount() <= bestCount) continue;
            var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id != null) { best = id.getPath(); bestCount = stack.getCount(); }
        }
        return best;
    }

    /**
     * Match full registry path tokens, not arbitrary substrings. Thus "stone" may match
     * stone_bricks, but can never accidentally match redstone_block.
     */
    static int findBlockSlot(CompanionEntity c, String keyword){
        String wanted = cleanPath(keyword);
        if (wanted.isBlank()) return -1;
        int best = -1, bestScore = 0;
        ItemStack hand = c.getMainHandItem();
        if (!hand.isEmpty() && hand.getItem() instanceof BlockItem) {
            var handKey = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(hand.getItem());
            if (handKey != null) {
                int score = blockMatchScore(cleanPath(handKey.getPath()), wanted);
                if (score > bestScore) { bestScore = score; best = -2; }
            }
        }
        for(int i=0;i<c.getCompanionInventory().getContainerSize();i++){
            ItemStack st = c.getCompanionInventory().getItem(i);
            if(st.isEmpty() || !(st.getItem() instanceof BlockItem)) continue;
            var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(st.getItem());
            if (key == null) continue;
            String path = cleanPath(key.getPath());
            int score = blockMatchScore(path, wanted);
            if(score > bestScore){ bestScore = score; best = i; }
        }
        return best;
    }

    private static String cleanPath(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "")
                .replaceAll("[^a-z0-9_ ]", " ").trim().replaceAll("[ \t]+", "_")
                .replaceAll("_+", "_");
    }

    private static int blockMatchScore(String path, String wanted) {
        if (path.equals(wanted)) return 100;
        String[] pathTokens = path.split("_");
        String[] wantedTokens = wanted.split("_");
        // A full phrase match ("oak planks" -> oak_planks) beats one-token variants.
        for (int i = 0; i <= pathTokens.length - wantedTokens.length; i++) {
            boolean matches = true;
            for (int j = 0; j < wantedTokens.length; j++) {
                if (!pathTokens[i + j].equals(wantedTokens[j])) { matches = false; break; }
            }
            if (matches) return wantedTokens.length > 1 ? 90 : 75;
        }
        // Common natural-language aliases, kept explicit to avoid fuzzy false positives.
        if (wanted.equals("wood") || wanted.equals("wooden")) {
            if (containsToken(pathTokens, "planks")) return 45;
            if (containsToken(pathTokens, "log") || containsToken(pathTokens, "wood")
                    || containsToken(pathTokens, "stem")) return 40;
        }
        if (wanted.equals("plank") && containsToken(pathTokens, "planks")) return 50;
        return 0;
    }

    private static boolean containsToken(String[] tokens, String query) {
        for (String token : tokens) if (token.equals(query)) return true;
        return false;
    }

    /** Replaceable ground positions in front of and around the companion, nearest first. */
    static List<BlockPos> candidateSpots(CompanionEntity c){
        Level lvl = c.level();
        BlockPos base = c.blockPosition();
        Direction facing = c.getDirection();
        List<BlockPos> out = new ArrayList<>();
        // Walk forward up to 6 blocks, placing on the ground; then widen to a small ring as a fallback.
        for(int step=1; step<=6; step++){
            BlockPos ahead = base.relative(facing, step);
            BlockPos spot = groundSpot(lvl, ahead);
            if(spot != null) out.add(spot);
        }
        for(int dx=-2; dx<=2; dx++) for(int dz=-2; dz<=2; dz++){
            if(dx==0 && dz==0) continue;
            BlockPos spot = groundSpot(lvl, base.offset(dx,0,dz));
            if(spot != null && !out.contains(spot)) out.add(spot);
        }
        return out;
    }

    /** From a column near {@code at}, find a replaceable cell that has a solid block beneath it. */
    private static BlockPos groundSpot(Level lvl, BlockPos at){
        for(int dy=1; dy>=-2; dy--){
            BlockPos pos = at.above(dy);
            if(!lvl.isLoaded(pos)) continue;
            BlockState here = lvl.getBlockState(pos);
            BlockState below = lvl.getBlockState(pos.below());
            boolean replaceable = here.canBeReplaced() || here.isAir();
            boolean floored = !below.isAir() && below.isFaceSturdy(lvl, pos.below(), Direction.UP);
            if(replaceable && floored) return pos.immutable();
        }
        return null;
    }
}
