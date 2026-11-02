package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.agent.InternalToolExecutor;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.task.TaskManager;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import com.yaseen.npcmind.api.Internal;

/**
 * High-level "gather resources" task. With a resource keyword it gathers that resource; with no keyword it
 * gathers a sensible starter batch (wood, then stone) so the player can just say "go get resources"
 * and the companion heads out and mines on its own. All movement/mining is the deterministic
 * task-queue path, so it does not depend on a command containing coordinates.
 */
@Internal
public final class GatherResourcesTool implements AITool {
    public String name(){ return "gather_resources"; }
    public String description(){ return "Autonomously go gather resources with NO coordinates. Give a resource keyword (wood, stone, dirt, sand, coal, iron...) and an amount, or omit it to gather a default starter batch (wood then stone). The companion walks to nearby matching blocks and mines them."; }
    public String schema(){ return "{\"resource\":string(optional),\"count\":number(optional)}"; }

    public String execute(CompanionEntity c, ServerPlayer p, String a){
        if(!c.getPermissions().allowed(ActionPermissions.Action.MINE)) return "DENIED permission=mine";
        String res = JsonArgs.string(a, "resource");
        Integer cn = JsonArgs.integer(a, "count");
        int count = cn == null ? 8 : Math.max(1, Math.min(32, cn));

        if(res != null && !res.isBlank()){
            res = res.toLowerCase(Locale.ROOT).trim();
            if(res.contains("wood") || res.contains("log") || res.contains("tree")){
                return prefix(InternalToolExecutor.invoke(c, p, "gather_wood", "{\"count\":" + count + "}", "internal-" + java.util.UUID.randomUUID()).result(), "wood");
            }
            return prefix(InternalToolExecutor.invoke(c, p, "gather_block", "{\"block\":\"" + res + "\",\"count\":" + count + "}", "internal-" + java.util.UUID.randomUUID()).result(), res);
        }

        // No keyword: default starter batch. Prefer wood; fall back to stone if no logs are loaded.
        TaskManager.cancelCurrentTask(c);
        String wood = InternalToolExecutor.invoke(c, p, "gather_wood", "{\"count\":" + count + "}", "internal-" + java.util.UUID.randomUUID()).result();
        if(wood != null && wood.startsWith("STARTED")) return "STARTED gather_resources plan=wood queued_from=" + wood;
        String stone = InternalToolExecutor.invoke(c, p, "gather_block", "{\"block\":\"stone\",\"count\":" + count + "}", "internal-" + java.util.UUID.randomUUID()).result();
        if(stone != null && stone.startsWith("STARTED")) return "STARTED gather_resources plan=stone queued_from=" + stone;
        return "FAILED nothing_to_gather_nearby wood=" + wood + " stone=" + stone;
    }

    private static String prefix(String r, String what){
        if(r == null) return "FAILED null_result resource=" + what;
        return r.startsWith("STARTED") ? "STARTED gather_resources resource=" + what + " via=" + r : r;
    }
}
