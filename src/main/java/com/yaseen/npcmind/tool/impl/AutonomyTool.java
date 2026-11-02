package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.autonomy.AutonomyController;
import com.yaseen.npcmind.autonomy.AutonomyState;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;

/** Configures autonomy and primes the deterministic router immediately. */
@Internal
public final class AutonomyTool implements AITool {
    public String name() { return "manage_autonomy"; }
    public String description() { return "Configure and activate real autonomy. Enabling routine immediately starts the selected safe mode; no fake actions are performed."; }
    public String schema() { return "{\"action\":\"status|mode|routine|enable|disable|suggestions|preference|survival(optional)\",\"value\":string(optional),\"eat\":boolean(optional)}"; }

    public String execute(CompanionEntity c, ServerPlayer p, String a) {
        String action = MoveToTool.str(a, "action");
        String value = MoveToTool.str(a, "value");
        // "eat" / "كل" is a direct order, not a configuration change: start eating right away.
        Boolean eat = JsonArgs.bool(a, "eat");
        if (Boolean.TRUE.equals(eat)) {
            boolean ok = c.startEatingFromInventory();
            return ok ? "STARTED eating from inventory" : "FAILED no_food_in_inventory";
        }
        if (action == null) action = "status";
        if ("survival".equals(action)) {
            c.getAutonomy().mode(AutonomyState.Mode.SURVIVAL);
            c.getAutonomy().routine(true);
            AutonomyController.clear(c.getUUID());
            return "STARTED autonomy mode=SURVIVAL progression=local_survival_routine";
        }
        if ("mode".equals(action) && value != null) {
            // Locale.ROOT: a Turkish default locale would map 'idle' to 'İDLE' and fail valueOf.
            try { c.getAutonomy().mode(AutonomyState.Mode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT))); }
            catch (Exception e) { return "ERROR invalid_mode expected=FOLLOW|BODYGUARD|EXPLORER|ENGINEER|MINER|FARMER|SURVIVAL|IDLE"; }
            // Consistency with action=survival: switching into SURVIVAL mode also enables the
            // survival routine, so both entry points behave identically.
            if (c.getAutonomy().mode() == AutonomyState.Mode.SURVIVAL) c.getAutonomy().routine(true);
        }
        if ("enable".equals(action)) c.getAutonomy().routine(true);
        if ("disable".equals(action)) c.getAutonomy().routine(false);
        if ("routine".equals(action)) {
            Boolean b = JsonArgs.bool(a, "value");
            if (b != null) c.getAutonomy().routine(b);
        }
        if ("suggestions".equals(action)) {
            Boolean b = JsonArgs.bool(a, "value");
            if (b != null) c.getAutonomy().suggestions(b);
        }
        if ("preference".equals(action) && value != null) c.getAutonomy().preference(value);
        if (c.getAutonomy().routine()) {
            AutonomyController.clear(c.getUUID());
            return "STARTED autonomy mode=" + c.getAutonomy().mode() + " router=deterministic_local " + c.getAutonomy().summary();
        }
        return "RESULT autonomy_disabled " + c.getAutonomy().summary();
    }
}
