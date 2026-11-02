package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.agent.InternalToolExecutor;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.task.TaskManager;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;

/**
 * Mine a block. Two modes:
 *  - absolute: x,y,z given -> mine exactly that block.
 *  - keyword:  block="stone" given (no coordinates) -> delegate to gather_block, which scans the
 *              loaded world for the nearest matching blocks.
 * The keyword mode exists because natural-language commands often omit exact x/y/z coordinates, so
 * "mine stone" used to fail with "arguments require x,y,z" every single time.
 */
@Internal
public final class MineBlockTool implements AITool {
    public String name(){return "mine_block";}
    public String description(){return "Mine blocks. Give x,y,z to mine one exact loaded block, or give a block keyword (stone, dirt, sand, coal, iron_ore...) with no coordinates and the companion finds and mines the nearest matching blocks itself.";}
    public String schema(){return "{\"x\":number(optional),\"y\":number(optional),\"z\":number(optional),\"block\":string(optional),\"resource\":string(optional),\"type\":string(optional),\"count\":number(optional)}";}
    public String execute(CompanionEntity c, ServerPlayer p, String a){
        if(!c.getPermissions().allowed(ActionPermissions.Action.MINE))return "DENIED permission=mine";
        Integer x=MoveToTool.num(a,"x"), y=MoveToTool.num(a,"y"), z=MoveToTool.num(a,"z");
        if ((x != null || y != null || z != null) && (x == null || y == null || z == null)) return "ERROR coordinates_require_x_y_z";
        if(x!=null && y!=null && z!=null) return TaskManager.mine(c,new BlockPos(x,y,z));

        // No usable coordinates: fall back to keyword search so the action still happens.
        String keyword = JsonArgs.string(a,"block");
        if(keyword == null || keyword.isBlank()) keyword = JsonArgs.string(a,"resource");
        if(keyword == null || keyword.isBlank()) keyword = JsonArgs.string(a,"type");
        // A bare "mine" (or "احفر") has no keyword: mine whatever block the companion is looking
        // at, within real player reach, instead of failing with an argument error.
        if(keyword == null || keyword.isBlank()){
            var hit = c.pick(4.5D, 1.0F, false);
            if(hit instanceof net.minecraft.world.phys.BlockHitResult bhr
                    && bhr.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK){
                return TaskManager.mine(c, bhr.getBlockPos());
            }
            return "FAILED nothing_in_reach look at a block or name one (block=stone)";
        }
        Integer cn = JsonArgs.integer(a,"count");
        int count = cn == null ? 4 : Math.max(1, Math.min(16, cn));
        String json = "{\"block\":\"" + keyword.replace("\"","") + "\",\"count\":" + count + "}";
        String r = InternalToolExecutor.invoke(c, p, "gather_block", json,
                "internal-" + java.util.UUID.randomUUID()).result();
        return r == null ? "FAILED null_result" : r;
    }
}
