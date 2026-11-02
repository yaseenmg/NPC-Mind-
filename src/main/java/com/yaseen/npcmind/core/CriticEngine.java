package com.yaseen.npcmind.core;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.api.Internal;

/** Post-action critic. Tool output is evidence, but world-state change is the final authority. */
@Internal
public final class CriticEngine {
    public record Verdict(boolean success, boolean progress, boolean retryable, String reason) {}

    public static Verdict evaluate(CompanionEntity c, WorldSnapshot before, WorldSnapshot after, String result) {
        if (result == null) return new Verdict(false, false, true, "null_result");
        if (result.startsWith("DENIED")) return new Verdict(false, false, false, result);
        if (result.startsWith("TOOL_ERROR")) return new Verdict(false, false, true, result);
        if (result.startsWith("FAILED") || result.startsWith("ERROR") || result.startsWith("TIMEOUT"))
            return new Verdict(false, false, true, result);
        WorldDiff d = WorldDiff.compare(before, after, result);
        boolean started = result.startsWith("STARTED");
        if (started) return new Verdict(false, false, true, "action_running");
        boolean explicitSuccess = result.startsWith("SUCCESS") || result.startsWith("RESULT");
        boolean progress = Math.abs(d.healthDelta()) > 0.01f || d.foodDelta() != 0 || d.inventorySlotDelta() != 0
                || d.inventoryChanged() || d.distanceMoved() > 0.75;
        // RESULT is intentionally not treated as success for mutating tools: read-only/control
        // tools can return RESULT, but world-changing actions must either show world/inventory
        // progress or carry an explicit terminal success with a concrete effect count.
        boolean concreteSuccess = explicitSuccess && (hasConcreteEffect(result) || isReadOnlyResult(result));
        return new Verdict(concreteSuccess || progress, progress || concreteSuccess, !progress && !concreteSuccess,
                concreteSuccess ? "verified_action_result" : (progress ? "verified_world_change" : "no_world_progress"));
    }
    private static boolean isReadOnlyResult(String result) {
        String r = result.toLowerCase(java.util.Locale.ROOT);
        return r.startsWith("result ") && (r.contains("inventory=") || r.contains("equipment=") ||
                r.contains("health=") || r.contains("permissions=") || r.contains("goal=") ||
                r.contains("world=") || r.contains("report=") || r.contains("enabled=") ||
                r.contains("status=") || r.contains("task="));
    }

    private static boolean hasConcreteEffect(String result) {
        String r = result.toLowerCase(java.util.Locale.ROOT);
        if (r.matches(".*\\b(placed|crafted|harvested|replanted|mined|taken|deposited|traded|moved|gave|lit|reached|equipped|started|queued|reserved|released|filled|learned|xp_pickup_window_enabled|xp_pickup_window_disabled|pickup_window_disabled)=([1-9][0-9]*|true|yes).*" )
                || r.contains("quest_started") || r.contains("quest_stopped") || r.contains("quest_already_active")
                || r.contains("combat_stopped") || r.contains("follow_enabled=true")
                || r.contains("reached ") || r.contains("already_air")
                // CraftItemTool emits "SUCCESS already_have=N output=X" (space-separated),
                // not the single token "already_have_output"; matching the bare prefix also
                // covers the internal CraftResult reason "already_have_output". The literal
                // "SUCCESS crafting_table_already_nearby" is a same-as-before success too.
                || r.contains("already_have") || r.contains("crafting_table_already_nearby")
                || r.contains("already_active") || r.contains("quest_not_active")
                // Tools that change knowledge/state rather than the world: a located structure and
                // a taught trigger are real, durable effects and must not read as failures.
                // filled_water_bucket=true is the FillWaterBucketTool success marker (the generic
                // "filled=" regex alternative cannot match it because of the underscore suffix).
                || r.contains("learned_trigger") || r.contains("filled_water_bucket=true")
                || r.startsWith("success structure=") || r.contains(" structure=")) return true;
        // No generic key=value fallback: diagnostics such as SUCCESS debug=1 or
        // SUCCESS cooldown=5 are not proof that the world changed. New mutating tools
        // must add a meaningful effect key to the allow-list above, or show progress in WorldDiff.
        return false;
    }

    private CriticEngine() {}
}
