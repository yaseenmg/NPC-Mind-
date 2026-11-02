package com.yaseen.npcmind.tool.impl;
import com.yaseen.npcmind.tool.AITool;import com.yaseen.npcmind.entity.CompanionEntity;import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;
@Internal
public final class RescueStatusTool implements AITool{public String name(){return "inspect_ai_state";}public String description(){return "Inspect the companion's persistent goal, permissions, world memory, experiment verification and decision journal.";}public String schema(){return "{}";}public String execute(CompanionEntity c,ServerPlayer p,String a){return "GOAL="+c.getGoal().summary()+"\nPERMISSIONS="+c.getPermissions().summary()+"\nWORLD="+c.getWorldMemory().summary()+"\nEXPERIMENTS="+c.getExperiments().summary()+"\nDECISIONS="+c.getDecisions().summary();}}
