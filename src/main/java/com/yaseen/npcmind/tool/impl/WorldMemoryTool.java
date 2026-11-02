package com.yaseen.npcmind.tool.impl;
import com.yaseen.npcmind.entity.CompanionEntity;import com.yaseen.npcmind.tool.AITool;import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;
@Internal
public final class WorldMemoryTool implements AITool{public String name(){return "inspect_world_memory";}public String description(){return "Read persistent observations, visited chunks, verified successes and failures learned only from the real world.";}public String schema(){return "{}";}public String execute(CompanionEntity c,ServerPlayer p,String a){return "RESULT "+c.getWorldMemory().summary();}}
