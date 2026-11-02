package com.yaseen.npcmind.tool.impl;
import com.yaseen.npcmind.tool.JsonArgs;
import com.yaseen.npcmind.advanced.ExplorationController;import com.yaseen.npcmind.tool.AITool;import com.yaseen.npcmind.entity.CompanionEntity;import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;
@Internal
public final class ExploreTool implements AITool{public String name(){return "explore_mode";}public String description(){return "Enable or disable real exploration; the controller selects nearby unvisited loaded-world chunks and navigates to them.";}public String schema(){return "{\"enabled\":boolean(optional)}";}public String execute(CompanionEntity c,ServerPlayer p,String a){Boolean v=bool(a);if(v==null)return "RESULT enabled="+ExplorationController.active(c);ExplorationController.set(c,v);return "RESULT enabled="+v;}private Boolean bool(String s){return JsonArgs.bool(s,"enabled");}}
