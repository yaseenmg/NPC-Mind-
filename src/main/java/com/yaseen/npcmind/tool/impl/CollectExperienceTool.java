package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.Config;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;

/** Explicitly enables bounded local pickup; XP orbs are handled by CompanionEntity's server tick. */
@Internal
public final class CollectExperienceTool implements AITool {
    @Override public String name() { return "collect_xp"; }
    @Override public String description() { return "Enable a bounded pickup window for nearby real XP orbs; no XP is fabricated."; }
    @Override public String schema() { return "{\"action\":\"enable|disable|status\",\"ticks\":number(optional)}"; }

    @Override
    public String execute(CompanionEntity c, ServerPlayer player, String arguments) {
        String action = MoveToTool.str(arguments, "action");
        if (action == null || action.isBlank()) action = "status";
        switch (action) {
            case "enable" -> {
                int ticks = Math.max(20, Math.min(12000, InspectNearbyEntitiesTool.number(arguments, "ticks", 200)));
                c.enableExperiencePickup(ticks);
                return "SUCCESS xp_pickup_window_enabled=true ticks=" + ticks;
            }
            case "disable" -> {
                c.disableExperiencePickupWindow();
                return Config.AUTO_PICKUP.get()
                        ? "RESULT xp_pickup_enabled=true auto_pickup_config=true"
                        : "SUCCESS xp_pickup_window_disabled=true";
            }
            case "status" -> {
                return "RESULT xp_pickup_enabled=" + c.isExperiencePickupEnabled()
                        + " auto_pickup_config=" + Config.AUTO_PICKUP.get()
                        + " xp_level=" + c.getExperienceLevel()
                        + " xp_progress=" + String.format(java.util.Locale.ROOT, "%.4f", c.getExperienceProgress())
                        + " xp_total=" + c.getTotalExperiencePoints();
            }
            default -> { return "ERROR invalid_action expected=enable|disable|status"; }
        }
    }
}
