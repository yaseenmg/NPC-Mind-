package com.yaseen.npcmind.team;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.nbt.CompoundTag;
import java.util.*;
import com.yaseen.npcmind.api.Internal;

/** Persistent team roles for multiple real companions. No phantom NPCs are created here. */
@Internal
public final class CompanionTeam {
    private String role="unassigned";
    private String teamId="";
    public void role(String r){if(r!=null&&!r.isBlank())role=r;}
    public void teamId(String id){if(id!=null)teamId=id;}
    public String role(){return role;}
    public String teamId(){return teamId;}
    public String summary(){return "teamId="+teamId+",role="+role;}
    public void save(CompoundTag tag){CompoundTag r=new CompoundTag();r.putString("teamId",teamId);r.putString("role",role);tag.put("NPCMindTeam",r);}
    public void load(CompoundTag tag){if(!tag.contains("NPCMindTeam",10))return;CompoundTag r=tag.getCompound("NPCMindTeam");teamId=r.getString("teamId");role=r.getString("role");}
    public static List<CompanionEntity> members(CompanionEntity c){if(c.level().getServer()==null)return List.of(c);List<CompanionEntity> out=new ArrayList<>();for(var l:c.level().getServer().getAllLevels())out.addAll(l.getEntitiesOfClass(CompanionEntity.class,new net.minecraft.world.phys.AABB(c.blockPosition()).inflate(128),x->x!=c&&Objects.equals(x.getTeamState().teamId(),c.getTeamState().teamId())&&!c.getTeamState().teamId().isBlank()));return out;}
}
