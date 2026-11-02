package com.yaseen.npcmind.tool.impl;
import com.yaseen.npcmind.tool.JsonArgs;
import com.yaseen.npcmind.task.TaskManager;
import com.yaseen.npcmind.entity.CompanionEntity;import com.yaseen.npcmind.tool.AITool;import net.minecraft.core.BlockPos;import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;
@Internal
public final class SmartNavigateTool implements AITool{
 public String name(){return "smart_navigate";} public String description(){return "Navigate to a real loaded-world coordinate using vanilla Pathfinder navigation, rejecting unloaded or immediately hazardous targets.";} public String schema(){return "{\"x\":number,\"y\":number,\"z\":number}";}
 public String execute(CompanionEntity c,ServerPlayer p,String a){Integer x=num(a,"x"),y=num(a,"y"),z=num(a,"z");if(x==null||y==null||z==null)return "ERROR coordinates_required";return TaskManager.move(c,new BlockPos(x,y,z));}
 private Integer num(String s,String n){return JsonArgs.integer(s,n);}}
