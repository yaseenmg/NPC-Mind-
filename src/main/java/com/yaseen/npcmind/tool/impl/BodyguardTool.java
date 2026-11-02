package com.yaseen.npcmind.tool.impl;
import com.yaseen.npcmind.tool.JsonArgs;
import com.yaseen.npcmind.advanced.BodyguardController;import com.yaseen.npcmind.tool.AITool;import com.yaseen.npcmind.entity.CompanionEntity;import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;
@Internal
public final class BodyguardTool implements AITool{public String name(){return "bodyguard_mode";}public String description(){return "Enable or disable real owner protection and rescue behavior.";}public String schema(){return "{\"enabled\":boolean(optional)}";}public String execute(CompanionEntity c,ServerPlayer p,String a){Boolean v=bool(a);if(v==null)return "RESULT enabled="+BodyguardController.enabled(c);BodyguardController.set(c,v);return "RESULT enabled="+v;}private Boolean bool(String s){return JsonArgs.bool(s,"enabled");}}
