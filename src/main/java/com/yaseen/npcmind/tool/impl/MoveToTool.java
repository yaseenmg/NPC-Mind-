package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.task.TaskManager;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;

@Internal
public final class MoveToTool implements AITool {
    public String name(){return "move_to";}
    public String description(){return "Move the companion through real Minecraft navigation to an absolute block position.";}
    public String schema(){return "{\"x\":number(optional),\"y\":number(optional),\"z\":number(optional)}";}
    public String execute(CompanionEntity c, ServerPlayer p, String a){
        Integer x=num(a,"x"), y=num(a,"y"), z=num(a,"z");
        // "come here" / "تعال" carries no coordinates: walk to the owner instead of failing with
        // an argument error. A partially supplied tuple is almost always a malformed command and
        // must not silently become a move to the owner's position.
        if ((x != null || y != null || z != null) && (x == null || y == null || z == null))
            return "ERROR coordinates_require_x_y_z";
        if(x==null&&y==null&&z==null){
            if(p==null) return "ERROR arguments require x,y,z";
            x=(int)Math.floor(p.getX()); y=(int)Math.floor(p.getY()); z=(int)Math.floor(p.getZ());
        }
        return TaskManager.move(c,new BlockPos(x,y,z));
    }
    static Integer num(String s,String n){return JsonArgs.integer(s,n);}
    static String str(String s,String n){String v=JsonArgs.string(s,n);return v.isEmpty()?null:v;}
}
