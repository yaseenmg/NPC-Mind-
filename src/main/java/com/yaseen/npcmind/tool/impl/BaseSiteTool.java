package com.yaseen.npcmind.tool.impl;
import com.yaseen.npcmind.advanced.BaseSiteEvaluator;import com.yaseen.npcmind.tool.AITool;import com.yaseen.npcmind.entity.CompanionEntity;import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;
@Internal
public final class BaseSiteTool implements AITool{public String name(){return "evaluate_base_site";}public String description(){return "Evaluate real loaded terrain around the companion and return a factual candidate site; it does not build anything.";}public String schema(){return "{\"radius\":number(optional)}";}public String execute(CompanionEntity c,ServerPlayer p,String a){Integer r=MoveToTool.num(a,"radius");int radius=r==null?32:Math.max(8,Math.min(24,r));return BaseSiteEvaluator.evaluate(c,radius);}}
