package com.yaseen.npcmind.tool.impl;
import com.yaseen.npcmind.advanced.EquipmentAnalyzer;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;
import java.util.Locale;
@Internal
public final class InspectEquipmentTool implements AITool {
 public String name(){return "inspect_equipment";} public String description(){return "Inspect the companion's equipped gear and inventory: main hand, off hand, armor slots and carried items.";} public String schema(){return "{\"slot\":\"all|mainhand|offhand|armor|inventory(optional)\"}";}
 public String execute(CompanionEntity c, ServerPlayer p,String a){
  String slot = JsonArgs.string(a, "slot");
  slot = slot == null ? "all" : slot.trim().toLowerCase(Locale.ROOT);
  if (slot.isEmpty()) slot = "all";
  boolean all = slot.equals("all");
  StringBuilder b = new StringBuilder();
  if (all || slot.equals("mainhand")) b.append("MAIN=").append(EquipmentAnalyzer.describe(c.getMainHandItem())).append('\n');
  if (all || slot.equals("offhand")) b.append("OFF=").append(EquipmentAnalyzer.describe(c.getOffhandItem())).append('\n');
  if (all || slot.equals("armor")) b.append(EquipmentAnalyzer.armorSummary(c));
  if (all || slot.equals("inventory"))
   for(int i=0;i<c.getCompanionInventory().getContainerSize();i++)
    if(!c.getCompanionInventory().getItem(i).isEmpty())
     b.append(i).append('=').append(EquipmentAnalyzer.describe(c.getCompanionInventory().getItem(i))).append('\n');
  if (b.length() == 0) return "FAILED unknown_slot=" + slot + " expected=all|mainhand|offhand|armor|inventory";
  return "RESULT " + b;
 }
}
