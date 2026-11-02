package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import com.yaseen.npcmind.state.ActionPermissions;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;
import com.yaseen.npcmind.api.Internal;

/**
 * Builds user-supplied ASCII blueprints against the real world:
 *
 * <ul>
 *   <li><b>Per-glyph block roles</b> — the optional {@code blocks} parameter maps glyphs to
 *       real block ids ("#=stone_bricks;D=oak_door;W=glass_pane"). The legacy single
 *       {@code block} parameter still works and defines the {@code #} glyph.</li>
 *   <li><b>Optional cells</b> — the {@code -} glyph is queued last and is allowed to be
 *       skipped by the normal BuildTask support-face rules without failing the build.</li>
 *   <li><b>Materials pre-calculation</b> — before queueing anything, the required cell count
 *       per block is compared with what the companion actually carries (inventory + main
 *       hand). An insufficient build is refused up front with exact numbers instead of a
 *       house that stops half-way.</li>
 *   <li><b>Bottom-up group order</b> — groups are queued bottom rows first so foundations
 *       and floors are placed before the rows above them.</li>
 * </ul>
 */
@Internal
public final class BuildBlueprintTool implements AITool {
    public String name(){return "build_blueprint";}
    public String description(){return "Build an ASCII blueprint in the real world. layout uses rows separated by /: # = main block, . or space = untouched, - = optional cell, letters = per-glyph roles from the blocks parameter (format \"D=oak_door;W=glass_pane\"). Checks carried materials first and reports exact shortages.";}
    public String schema(){return "{\"block\":string,\"layout\":string,\"blocks\":string(optional, glyph=block_id;...),\"x\":number(optional),\"y\":number(optional),\"z\":number(optional)}";}

    public String execute(CompanionEntity c, ServerPlayer p, String a){
        if(!c.getPermissions().allowed(ActionPermissions.Action.BUILD)) return "DENIED permission=build";
        String id=str(a,"block"), layout=str(a,"layout"), blocksParam=str(a,"blocks");
        Integer x=MoveToTool.num(a,"x"), y=MoveToTool.num(a,"y"), z=MoveToTool.num(a,"z");
        if(id.isBlank()) return "FAILED block id required (name the block to build the blueprint with)";
        if(layout.isBlank())return "FAILED layout is required (rows separated by /, # = block)";
        if ((x != null || y != null || z != null) && (x == null || y == null || z == null)) return "ERROR coordinates_require_x_y_z";
        // Missing coordinates: build in front of the companion rather than refusing.
        if(x==null&&y==null&&z==null){
            BlockPos ahead=c.blockPosition().relative(c.getDirection(),2);
            x=ahead.getX(); y=ahead.getY(); z=ahead.getZ();
        }
        // Glyph -> block id map. The main block always backs '#'; extra roles come from blocks.
        java.util.Map<Character,String> glyphIds=new java.util.LinkedHashMap<>();
        glyphIds.put('#', id);
        if(!blocksParam.isBlank()){
            for(String pair : blocksParam.split(";")){
                if(pair.isBlank()) continue;
                // Format: single glyph, '=', block id — e.g. "D=oak_door;W=glass_pane".
                if(pair.length() < 3 || pair.charAt(1) != '=')
                    return "FAILED blocks_format expected glyph=id pairs separated by ; got="+pair;
                char glyph=pair.charAt(0);
                if(glyph=='.'||glyph=='-'||glyph==' ') return "FAILED blocks_cannot_redefine_reserved_glyph="+glyph;
                String glyphId=pair.substring(2).trim();
                if(glyphId.isBlank()) return "FAILED blocks_empty_block_id_for_glyph="+glyph;
                glyphIds.put(glyph, glyphId);
            }
        }
        // Resolve every role to a live block once, before touching the queue.
        java.util.Map<Character,Block> glyphBlocks=new java.util.LinkedHashMap<>();
        for(var entry : glyphIds.entrySet()){
            ResourceLocation rid=ResourceLocation.tryParse(entry.getValue());
            if(rid==null) return "FAILED invalid_block_id="+entry.getValue();
            Block block=ForgeRegistries.BLOCKS.getValue(rid);
            if(block==null) return "FAILED unknown live block="+entry.getValue();
            glyphBlocks.put(entry.getKey(), block);
        }
        String[] rows=layout.split("/",-1); if(rows.length>16)return "FAILED blueprint height > 16";
        BlockPos base=new BlockPos(x,y,z);
        // One target list per glyph role; '-' optional cells are tracked separately.
        java.util.Map<Character,java.util.List<BlockPos>> byGlyph=new java.util.LinkedHashMap<>();
        java.util.List<BlockPos> optional=new java.util.ArrayList<>();
        int rowMaxWidth=0;
        for(int row=0;row<rows.length;row++){
            rowMaxWidth=Math.max(rowMaxWidth,rows[row].length());
            for(int col=0;col<rows[row].length();col++){
                char glyph=rows[row].charAt(col);
                if(glyph=='.'||glyph==' ') continue;
                // y offset flips the text orientation: the FIRST layout row is the TOP of the
                // structure, matching how a player sketches a wall on paper.
                BlockPos pos=base.offset(col,rows.length-1-row,0);
                if(glyph=='-'){ optional.add(pos); continue; }
                if(!glyphBlocks.containsKey(glyph)) return "FAILED undefined_glyph="+glyph+" add it to blocks or use # . -";
                byGlyph.computeIfAbsent(glyph,k->new java.util.ArrayList<>()).add(pos);
            }
        }
        if(rowMaxWidth>16)return "FAILED blueprint width > 16";
        if(byGlyph.isEmpty()&&optional.isEmpty()) return "FAILED blueprint_no_blocks_placed requested_marks=0 block="+id;
        // Materials pre-calculation: required mandatory cells per block versus what the
        // companion actually carries (main hand + inventory). Optional cells are not counted;
        // they are a best-effort extra. Refusing up front prevents half-built houses.
        java.util.Map<Block,Integer> required=new java.util.LinkedHashMap<>();
        for(var entry : byGlyph.entrySet()){
            required.merge(glyphBlocks.get(entry.getKey()), entry.getValue().size(), Integer::sum);
        }
        for(var entry : required.entrySet()){
            int have=availableCount(c, entry.getKey());
            if(have < entry.getValue()){
                ResourceLocation key=ForgeRegistries.BLOCKS.getKey(entry.getKey());
                return "FAILED insufficient_materials block="+(key==null?"?":key)
                        +" have="+have+" required="+entry.getValue()
                        +" (optional_cells="+optional.size()+" are not required)";
            }
        }
        // Bottom-up ordering: sort each group by y ascending so foundations/floors are queued
        // before the rows above them, regardless of sketch order.
        for(var list : byGlyph.values()) list.sort(java.util.Comparator.comparingInt(BlockPos::getY));
        optional.sort(java.util.Comparator.comparingInt(BlockPos::getY));
        boolean first=true;
        String lastResult=null;
        for(var entry : byGlyph.entrySet()){
            Block block=glyphBlocks.get(entry.getKey());
            ResourceLocation key=ForgeRegistries.BLOCKS.getKey(block);
            String label=id+"/blueprint/"+(key==null?"glyph":key.getPath());
            if(first){
                lastResult=com.yaseen.npcmind.task.TaskManager.build(c, entry.getValue(), block, label);
                if(lastResult==null || !lastResult.startsWith("STARTED")) return lastResult;
                first=false;
            } else {
                lastResult=com.yaseen.npcmind.task.TaskManager.queueBuild(c, entry.getValue(), block, label);
                if(lastResult==null || !lastResult.startsWith("QUEUED")) return lastResult;
            }
        }
        if(!optional.isEmpty()){
            if(first){ // blueprint with only optional cells: build them as the main group
                lastResult=com.yaseen.npcmind.task.TaskManager.build(c, optional, glyphBlocks.get('#'), id+"/blueprint/optional");
                return lastResult;
            }
            lastResult=com.yaseen.npcmind.task.TaskManager.queueBuild(c, optional, glyphBlocks.get('#'), id+"/blueprint/optional");
            if(lastResult==null || !lastResult.startsWith("QUEUED")) return lastResult;
        }
        int total=optional.size();
        for(var list : byGlyph.values()) total+=list.size();
        return "STARTED action_id=blueprint groups="+byGlyph.size()+" cells="+total+" block="+id;
    }

    /** How many of {@code block} the companion actually carries (main hand + inventory). */
    private static int availableCount(CompanionEntity c, Block block){
        int n=0;
        ItemStack hand=c.getMainHandItem();
        if(hand.getItem() instanceof BlockItem bi && bi.getBlock()==block) n+=hand.getCount();
        for(int i=0;i<c.getCompanionInventory().getContainerSize();i++){
            ItemStack s=c.getCompanionInventory().getItem(i);
            if(s.getItem() instanceof BlockItem bi2 && bi2.getBlock()==block) n+=s.getCount();
        }
        return n;
    }
    private static String str(String j,String k){return JsonArgs.string(j,k);}
}
