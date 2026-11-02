package com.yaseen.npcmind.tool.impl;
import com.yaseen.npcmind.entity.CompanionEntity;import com.yaseen.npcmind.team.CompanionTeam;import com.yaseen.npcmind.tool.AITool;import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;
@Internal
public final class TeamTool implements AITool{public String name(){return "manage_companion_team";}public String description(){return "Assign this real companion to a team and role; existing companions only, no phantom NPC creation.";}public String schema(){return "{\"team_id\":\"text(optional)\",\"role\":\"text(optional)\"}";}public String execute(CompanionEntity c,ServerPlayer p,String a){String id=MoveToTool.str(a,"team_id"),role=MoveToTool.str(a,"role");if(id!=null)c.getTeamState().teamId(id);if(role!=null)c.getTeamState().role(role);return "RESULT "+c.getTeamState().summary()+" members="+CompanionTeam.members(c).size();}}
