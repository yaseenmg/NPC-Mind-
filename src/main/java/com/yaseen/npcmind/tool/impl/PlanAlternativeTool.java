package com.yaseen.npcmind.tool.impl;
import com.yaseen.npcmind.autonomy.AlternativePlanner;import com.yaseen.npcmind.entity.CompanionEntity;import com.yaseen.npcmind.tool.AITool;import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;
@Internal
public final class PlanAlternativeTool implements AITool{public String name(){return "record_plan_failure";}public String description(){return "Record why a real plan failed so the next planning pass can choose a different strategy.";}public String schema(){return "{\"plan\":\"text\",\"reason\":\"text\"}";}public String execute(CompanionEntity c,ServerPlayer p,String a){String plan=MoveToTool.str(a,"plan"),reason=MoveToTool.str(a,"reason");if(plan==null||reason==null)return "ERROR plan_and_reason_required";AlternativePlanner.failed(c,plan,reason);return "RESULT recorded_failure=true";}}
