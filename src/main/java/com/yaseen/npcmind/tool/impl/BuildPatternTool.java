package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import com.yaseen.npcmind.state.ActionPermissions;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import com.yaseen.npcmind.api.Internal;

@Internal
public final class BuildPatternTool implements AITool {
    public String name(){return "build_pattern";}
    public String description(){return "Place a simple real block pattern from the companion inventory. Supports wall, floor, or line; every placed block is consumed from the real inventory and only replaceable air/water positions are changed.";}
    public String schema(){return "{\"block\":string(optional),\"pattern\":\"wall|floor|line|house|tower|bridge(optional)\",\"width\":number(optional),\"height\":number(optional),\"length\":number(optional),\"x\":number(optional),\"y\":number(optional),\"z\":number(optional)}";}
    public String execute(CompanionEntity c, ServerPlayer p, String a){
        if(!c.getPermissions().allowed(ActionPermissions.Action.BUILD)) return "DENIED permission=build";
        String id=string(a,"block");
        // No block named: use whatever building block the companion actually carries, so
        // "build a wall" / "ابن جدار" works without the owner naming a block id.
        if(id.isBlank()){
            id = mostAbundantBlockId(c);
            if(id == null) return "FAILED no_building_block_in_inventory";
        }
        ResourceLocation resourceId=ResourceLocation.tryParse(id);
        Block b=resourceId==null?null:ForgeRegistries.BLOCKS.getValue(resourceId);
        // Generic words like "planks" and "wood" are inventory aliases, not real registry ids.
        // Resolve those to a real BlockItem first; never create a block or substitute an absent item.
        if (b == null || b == net.minecraft.world.level.block.Blocks.AIR) {
            int slot = PlaceBlockTool.findBlockSlot(c, id);
            // -2 = already in the main hand (PlaceBlockTool convention). Ignoring it made
            // "build a wall" fail with block_not_found whenever the material was equipped.
            ItemStack stack = slot == -2 ? c.getMainHandItem()
                    : slot >= 0 ? c.getCompanionInventory().getItem(slot) : ItemStack.EMPTY;
            if (!stack.isEmpty()) {
                b = Block.byItem(stack.getItem());
                ResourceLocation actual = ForgeRegistries.BLOCKS.getKey(b);
                if (actual != null) id = actual.toString();
            }
        }
        if(b==null || b==net.minecraft.world.level.block.Blocks.AIR)return "FAILED block_not_found_in_inventory_or_registry="+id;
        String pattern=string(a,"pattern"); if(pattern.isBlank())pattern="wall";
        int w=clamp(MoveToTool.num(a,"width")==null?5:MoveToTool.num(a,"width"),1,16), h=clamp(MoveToTool.num(a,"height")==null?3:MoveToTool.num(a,"height"),1,16), l=clamp(MoveToTool.num(a,"length")==null?5:MoveToTool.num(a,"length"),1,16);
        // A house needs enough wall width/depth for corners and at least two blocks of doorway.
        if (pattern.equalsIgnoreCase("house")) { w = Math.max(3, w); h = Math.max(2, h); l = Math.max(3, l); }
        Integer x=MoveToTool.num(a,"x"),y=MoveToTool.num(a,"y"),z=MoveToTool.num(a,"z");
        if ((x != null || y != null || z != null) && (x == null || y == null || z == null)) return "ERROR coordinates_require_x_y_z";
        // No coordinates: build right in front of the companion instead of failing.
        if(x==null&&y==null&&z==null){
            BlockPos ahead=c.blockPosition().relative(c.getDirection(),2);
            x=ahead.getX(); y=ahead.getY(); z=ahead.getZ();
        }
        List<BlockPos> targets=new ArrayList<>(); BlockPos base=new BlockPos(x,y,z);
        // A house uses a real two-block doorway. If the inventory contains a DoorItem, queue
        // its real placement after the shell; otherwise leave the opening and report the missing
        // door explicitly instead of silently claiming the house is fully enclosed.
        BlockPos doorTarget = null;
        Block doorBlock = null;
        net.minecraft.core.Direction forward = c.getDirection();
        net.minecraft.core.Direction side = forward.getClockWise();
        if(pattern.equalsIgnoreCase("line")) {
            for(int i=0;i<l;i++) targets.add(base.relative(forward, i));
        } else if(pattern.equalsIgnoreCase("bridge")) {
            // A compact flat bridge aligned with the companion's facing, not hard-coded east.
            int bridgeWidth = Math.max(1, Math.min(5, w));
            for(int step=0;step<l;step++) for(int lane=0;lane<bridgeWidth;lane++)
                targets.add(base.relative(forward, step).relative(side, lane - bridgeWidth / 2));
        } else if(pattern.equalsIgnoreCase("floor")) {
            for(int dx=0;dx<w;dx++) for(int dz=0;dz<l;dz++)
                targets.add(base.relative(forward, dz).relative(side, dx));
        } else if(pattern.equalsIgnoreCase("house")) {
            // Hollow shell: four walls, a flat roof and a 1x2 doorway in the front wall.
            for(int dx=0;dx<w;dx++) for(int dy=0;dy<h;dy++) {
                targets.add(base.relative(forward, 0).relative(side, dx).above(dy));
                targets.add(base.relative(forward, l-1).relative(side, dx).above(dy));
            }
            for(int dz=1;dz<l-1;dz++) for(int dy=0;dy<h;dy++) {
                targets.add(base.relative(forward, dz).relative(side, 0).above(dy));
                targets.add(base.relative(forward, dz).relative(side, w-1).above(dy));
            }
            for(int dx=0;dx<w;dx++) for(int dz=0;dz<l;dz++)
                targets.add(base.relative(forward, dz).relative(side, dx).above(h));
            int doorX=w/2;
            doorTarget = base.relative(forward, 0).relative(side, doorX).immutable();
            targets.remove(doorTarget);
            if(h>1) targets.remove(doorTarget.above(1));
            doorBlock = findDoorBlock(c);
        } else if(pattern.equalsIgnoreCase("tower")) {
            int tw=Math.max(3, Math.min(w, 5)), tl=Math.max(3, Math.min(l, 5));
            for(int dy=0;dy<h;dy++) {
                for(int dx=0;dx<tw;dx++) {
                    targets.add(base.relative(forward,0).relative(side,dx).above(dy));
                    targets.add(base.relative(forward,tl-1).relative(side,dx).above(dy));
                }
                for(int dz=1;dz<tl-1;dz++) {
                    targets.add(base.relative(forward,dz).relative(side,0).above(dy));
                    targets.add(base.relative(forward,dz).relative(side,tw-1).above(dy));
                }
            }
            for(int dx=0;dx<tw;dx++) for(int dz=0;dz<tl;dz++)
                targets.add(base.relative(forward,dz).relative(side,dx).above(h));
            targets.remove(base.relative(forward,0).relative(side,tw/2));
            if(h>1) targets.remove(base.relative(forward,0).relative(side,tw/2).above(1));
        } else if(pattern.equalsIgnoreCase("wall")) {
            for(int dx=0;dx<w;dx++) for(int dy=0;dy<h;dy++)
                targets.add(base.relative(side, dx).above(dy));
        } else {
            return "FAILED unsupported_pattern=" + pattern;
        }
        if (targets.isEmpty()) return "FAILED no_blocks_placed requested=0 block=" + id + " pattern=" + pattern;
        // Hand the whole cell list to the real progressive BuildTask: it walks into placement
        // reach, validates every cell and places one block at a time instead of materialising
        // the entire pattern in a single tick.
        if (pattern.equalsIgnoreCase("house") && h >= 2 && doorTarget != null) {
            if (doorBlock != null) {
                return com.yaseen.npcmind.task.TaskManager.buildWithFinisher(
                        c, targets, b, id + "/" + pattern, doorTarget, doorBlock, "minecraft:door/house-finish");
            }
            return com.yaseen.npcmind.task.TaskManager.build(c, targets, b, id + "/" + pattern)
                    + " house_finish=doorway_left_open reason=door_not_in_inventory";
        }
        return com.yaseen.npcmind.task.TaskManager.build(c, targets, b, id + "/" + pattern);
    }

    /** Finds a real DoorBlock backed by an item already in the companion's equipment/inventory. */
    private static Block findDoorBlock(CompanionEntity c) {
        ItemStack hand = c.getMainHandItem();
        if (!hand.isEmpty() && hand.getItem() instanceof net.minecraft.world.item.BlockItem
                && Block.byItem(hand.getItem()) instanceof net.minecraft.world.level.block.DoorBlock
                && com.yaseen.npcmind.autonomy.ResourceLedger.canConsume(c, hand, 1)) {
            return Block.byItem(hand.getItem());
        }
        for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
            ItemStack stack = c.getCompanionInventory().getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem)
                    || !com.yaseen.npcmind.autonomy.ResourceLedger.canConsume(c, stack, 1)) continue;
            Block candidate = Block.byItem(stack.getItem());
            if (candidate instanceof net.minecraft.world.level.block.DoorBlock) return candidate;
        }
        return null;
    }

    /** Most plentiful BlockItem in the companion's inventory, as a registry id, or null. */
    private static String mostAbundantBlockId(CompanionEntity c){
        String bestId=null; int bestCount=0;
        // The main hand is a real storage location: BuildTask equips the exact material it
        // places, so an equipped builder can hold all of its blocks in the hand while the
        // inventory shows none. Skipping the hand made "build a wall" fail with
        // no_building_block_in_inventory even though the material was available.
        java.util.List<ItemStack> stacks = new java.util.ArrayList<>();
        stacks.add(c.getMainHandItem());
        for(int i=0;i<c.getCompanionInventory().getContainerSize();i++){
            stacks.add(c.getCompanionInventory().getItem(i));
        }
        for(ItemStack st : stacks){
            if(st.isEmpty()||!(st.getItem() instanceof net.minecraft.world.item.BlockItem)
                    || !com.yaseen.npcmind.autonomy.ResourceLedger.canConsume(c, st, 1))continue;
            Block blk=Block.byItem(st.getItem());
            if(blk==null||blk==net.minecraft.world.level.block.Blocks.AIR||!blk.defaultBlockState().blocksMotion())continue;
            if(st.getCount()>bestCount){
                ResourceLocation key=ForgeRegistries.BLOCKS.getKey(blk);
                if(key!=null){bestCount=st.getCount();bestId=key.toString();}
            }
        }
        return bestId;
    }
    private static int clamp(int x,int a,int b){return Math.max(a,Math.min(b,x));}
    private static String string(String j,String k){return JsonArgs.string(j,k);}
}
