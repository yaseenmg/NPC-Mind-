package com.yaseen.npcmind.tool.impl;
import com.yaseen.npcmind.entity.CompanionEntity;import com.yaseen.npcmind.autonomy.ResourceLedger;import com.yaseen.npcmind.tool.AITool;import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;
@Internal
public final class DailyReportTool implements AITool{public String name(){return "daily_report";}public String description(){return "Generate a factual report from real world observations, goals, decisions, inventory and maintenance data.";}public String schema(){return "{}";}public String execute(CompanionEntity c,ServerPlayer p,String a){return "RESULT report={mode:"+c.getAutonomy().mode()+",goal:"+c.getGoal().summary()+",world:"+c.getWorldMemory().summary()+",inventory:"+ResourceLedger.snapshot(c)+",decisions:"+c.getDecisions().summary()+",team:"+c.getTeamState().summary()+"}";}}
