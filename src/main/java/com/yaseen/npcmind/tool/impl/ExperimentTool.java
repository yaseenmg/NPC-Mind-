package com.yaseen.npcmind.tool.impl;
import com.yaseen.npcmind.tool.AITool;import com.yaseen.npcmind.entity.CompanionEntity;import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;
@Internal
public final class ExperimentTool implements AITool{public String name(){return "record_verification";}public String description(){return "Record a claim as verified by a real tool/result, or as unknown; never record guesses as facts.";}public String schema(){return "{\"status\":\"verified|unknown\",\"claim\":\"text\"}";}public String execute(CompanionEntity c,ServerPlayer p,String a){String s=MoveToTool.str(a,"status"),claim=MoveToTool.str(a,"claim");if(claim==null||claim.isBlank())return "ERROR claim_required";if("verified".equals(s))c.getExperiments().verified(claim);else if("unknown".equals(s))c.getExperiments().unknown(claim);else return "ERROR status_must_be_verified_or_unknown";return "RESULT "+c.getExperiments().summary();}}
