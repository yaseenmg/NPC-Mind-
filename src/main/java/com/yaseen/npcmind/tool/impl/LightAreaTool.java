package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.agent.InternalToolExecutor;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.security.WorldActionSecurity;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;
import com.yaseen.npcmind.api.Internal;

/**
 * "light up this area". Places a light source (torch by default, or candle /
 * lantern) on the ground every {@code spacing} blocks along the companion's facing direction. If
 * the companion has no light source but can craft torches from its own materials, it crafts first.
 */
@Internal
public final class LightAreaTool implements AITool {
    public String name(){ return "light_area"; }
    public String description(){ return "Light up an area by placing a light source (torch, candle or lantern) on the ground every N blocks in front of the companion. No coordinates needed. If it has no torches but owns coal/sticks, it crafts torches first."; }
    public String schema(){ return "{\"light\":\"torch|candle|lantern(optional)\",\"spacing\":number(optional),\"count\":number(optional)}"; }

    public String execute(CompanionEntity c, ServerPlayer p, String a){
        if(!c.getPermissions().allowed(ActionPermissions.Action.BUILD)) return "DENIED permission=build";
        if (!(c.getOwnerPlayer() instanceof ServerPlayer owner)) return "DENIED owner is not online; world mutation is blocked";
        String light = JsonArgs.string(a, "light");
        if(light == null || light.isBlank()) light = "torch";
        light = light.toLowerCase(Locale.ROOT).trim();
        Integer sp = JsonArgs.integer(a, "spacing");
        int spacing = sp == null ? 5 : Math.max(2, Math.min(10, sp));
        Integer cn = JsonArgs.integer(a, "count");
        int count = cn == null ? 8 : Math.max(1, Math.min(32, cn));

        // Make sure we actually own a light block; try to craft torches if the player asked for torches.
        // PlaceBlockTool.findBlockSlot returns -2 when the light source is already in the main hand;
        // the old code treated -2 like "missing", tried to craft torches anyway, then indexed
        // getItem(-2) and died with FAILED invalid_build_block.
        int slot = PlaceBlockTool.findBlockSlot(c, light);
        boolean crafted = false;
        if (slot == -2) light = light.contains("torch") || light.contains("lantern") ? light : "torch";
        if (slot < 0 && slot != -2 && (light.contains("torch") || light.contains("lantern"))){
            String r = InternalToolExecutor.invoke(c, p, "craft_item", "{\"output\":\"torch\",\"count\":" + count + "}", "internal-" + java.util.UUID.randomUUID()).result();
            crafted = r != null && r.startsWith("SUCCESS");
            slot = PlaceBlockTool.findBlockSlot(c, "torch");
            if(slot >= 0) light = "torch";
        }
        // -2 means the light source is already in the main hand (PlaceBlockTool convention).
        // The final guard must accept it exactly like the crafting-guard above does, otherwise
        // "light this area" always fails while holding the torch.
        if(slot < 0 && slot != -2) return "FAILED no_light_source_and_cannot_craft=" + light;
        ItemStack lightStack = slot == -2 ? c.getMainHandItem() : c.getCompanionInventory().getItem(slot);
        Block block = Block.byItem(lightStack.getItem());
        if(block == null) return "FAILED not_a_placeable_light=" + light;

        Level lvl = c.level();
        Direction facing = c.getDirection();
        BlockPos base = c.blockPosition();
        // Collect the light positions, then let the progressive BuildTask walk to each one and
        // place it. The old loop placed up to 32 torches at spacing 5 (160 blocks away) in a
        // single tick, from wherever the companion stood.
        java.util.List<BlockPos> spots = new java.util.ArrayList<>();
        for(int i=1; i<=count; i++){
            BlockPos ahead = base.relative(facing, i * spacing);
            BlockPos spot = airOnFloor(lvl, ahead);
            if(spot == null) continue;
            if(!block.defaultBlockState().canSurvive(lvl, spot)) continue;
            if(!WorldActionSecurity.canPlaceBlock(c, owner, spot)) continue;
            spots.add(spot);
        }
        if(spots.isEmpty()) return "FAILED no_valid_spot light=" + light;
        String r = com.yaseen.npcmind.task.TaskManager.build(c, spots, block, light + "/light_area");
        return crafted ? r + " crafted_torches=true" : r;
    }

    /** Air cell that sits directly on a solid floor (so a torch/candle can stand). */
    private static BlockPos airOnFloor(Level lvl, BlockPos at){
        for(int dy=1; dy>=-2; dy--){
            BlockPos pos = at.above(dy);
            if(!lvl.isLoaded(pos)) continue;
            BlockState here = lvl.getBlockState(pos);
            BlockState below = lvl.getBlockState(pos.below());
            if((here.isAir() || here.canBeReplaced()) && below.isFaceSturdy(lvl, pos.below(), Direction.UP))
                return pos.immutable();
        }
        return null;
    }
}
