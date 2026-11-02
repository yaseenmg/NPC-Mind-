package com.yaseen.npcmind.tool.impl;
import com.yaseen.npcmind.advanced.EquipmentAnalyzer;
import com.yaseen.npcmind.advanced.AdaptiveCombatController;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.tool.AITool;
import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;
@Internal
public final class CombatStatusTool implements AITool {
 public String name(){return "combat_status";} public String description(){return "Report the companion's current combat state: current target, health, armor, weapon and whether it is retreating.";} public String schema(){return "{}";}
 public String execute(CompanionEntity c,ServerPlayer p,String a){
  net.minecraft.world.entity.LivingEntity target=c.getTarget();
  String targetText = target == null ? "none" : target.getName().getString() + " (" + target.getUUID() + ")";
  return "target=" + targetText
   + "\nretreating=" + AdaptiveCombatController.isRetreating(c)
   + "\nhealth=" + c.getHealth() + "/" + c.getMaxHealth()
   + "\nmain=" + EquipmentAnalyzer.describe(c.getMainHandItem())
   + "\noff=" + EquipmentAnalyzer.describe(c.getOffhandItem())
   + "\narmor=\n" + EquipmentAnalyzer.armorSummary(c);
 }
}
