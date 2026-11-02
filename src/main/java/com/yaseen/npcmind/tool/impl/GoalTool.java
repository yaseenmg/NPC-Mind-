package com.yaseen.npcmind.tool.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.tool.AITool;
import net.minecraft.server.level.ServerPlayer;
import java.util.ArrayList;
import java.util.List;
import com.yaseen.npcmind.api.Internal;

@Internal
public final class GoalTool implements AITool {
    public String name(){return "manage_long_term_goal";}
    public String description(){return "Create/resume a persistent project with explicit steps, advance only after real verification, complete or cancel it.";}
    public String schema(){return "{\"action\":\"start|step|complete|cancel|status\",\"title\":\"text\"(optional),\"plan\":\"text\"(optional),\"steps\":[\"step 1\",\"step 2\"](optional)}";}
    public String execute(CompanionEntity c,ServerPlayer p,String a){
        String action=MoveToTool.str(a,"action");
        if("start".equals(action)){
            String t=MoveToTool.str(a,"title"),pl=MoveToTool.str(a,"plan");
            if(t==null||t.isBlank())return "ERROR title_required";
            c.getGoal().start(t,pl==null?"":pl,p.serverLevel().getGameTime());
            c.getGoal().verifiedBaseline(c.getDecisions().sequence());
            try{
                var root=JsonParser.parseString(a).getAsJsonObject();
                if(root.has("steps")&&root.get("steps").isJsonArray()){
                    JsonArray arr=root.getAsJsonArray("steps"); List<String> steps=new ArrayList<>();
                    for(int i=0;i<arr.size()&&steps.size()<32;i++) if(arr.get(i).isJsonPrimitive()) steps.add(arr.get(i).getAsString());
                    c.getGoal().setSteps(steps);
                }
            } catch (RuntimeException malformedSteps) {
                com.yaseen.npcmind.NPCMind.LOG.debug("[NPCMind] ignored malformed optional steps for long-term goal '{}': {}",
                        t, malformedSteps.getMessage());
            }
            return "RESULT goal_started=true "+c.getGoal().summary();
        }
        if("step".equals(action)){
            if(!c.getGoal().active())return "ERROR no_active_goal";
            if(!c.getDecisions().hasSuccessAfter(c.getGoal().verifiedBaseline())) return "DENIED goal_step_requires_new_verified_success_from_a_real_tool";
            c.getGoal().verifiedBaseline(c.getDecisions().sequence());
            c.getGoal().stepDone(); return "RESULT "+c.getGoal().summary();
        }
        if("complete".equals(action)){
            if(!c.getGoal().active()) return "ERROR no_active_goal";
            if(!c.getDecisions().hasSuccessAfter(c.getGoal().verifiedBaseline())) return "DENIED goal_completion_requires_new_verified_success_from_a_real_tool";
            c.getGoal().verifiedBaseline(c.getDecisions().sequence());
            c.getGoal().complete(); return "RESULT "+c.getGoal().summary();
        }
        if("cancel".equals(action)){c.getGoal().cancel();return "RESULT "+c.getGoal().summary();}
        return "RESULT "+c.getGoal().summary();
    }
}
