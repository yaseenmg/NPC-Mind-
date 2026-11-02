package com.yaseen.npcmind.tool.impl;
import com.yaseen.npcmind.tool.JsonArgs;
import com.yaseen.npcmind.autonomy.MaintenanceController;import com.yaseen.npcmind.entity.CompanionEntity;import com.yaseen.npcmind.tool.AITool;import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;
@Internal
public final class MaintenanceTool implements AITool{public String name(){return "inspect_base_maintenance";}public String description(){return "Inspect nearby real block entities for low Forge Energy and report maintenance candidates.";}public String schema(){return "{\"radius\":number(optional)}";}public String execute(CompanionEntity c,ServerPlayer p,String a){int r=num(a,"radius",12);return MaintenanceController.scan(c,r);}private int num(String s,String n,int d){return JsonArgs.integer(s,n,d);}}
