package com.yaseen.npcmind.agent;

import net.minecraft.nbt.CompoundTag;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.yaseen.npcmind.api.Internal;

/**
 * Deterministic lease for a single active work plan.
 * One writer owns a companion plan, every step receives an action id, and
 * repeated identical failures become blocked instead of looping forever.
 */
@Internal
public final class PlanExecutionController {
    private static final int MAX_SAME_FAILURES = 2;
    private static final Map<UUID, Checkpoint> PLANS = new ConcurrentHashMap<>();

    public record Checkpoint(String goal, String step, String actionId, String lastResult,
                             int failures, boolean blocked) {}

    private PlanExecutionController() {}

    public static Checkpoint begin(UUID companionId, String goal) {
        return PLANS.compute(companionId, (id, old) -> {
            // Reset the plan only when the goal actually changes. The old `contains`
            // test kept a stale checkpoint whenever the new goal merely CONTAINED the
            // previous one as a substring (and always kept it when old.goal() was empty).
            String oldGoal = old == null || old.goal() == null ? "" : old.goal().trim();
            String newGoal = goal == null ? "" : goal.trim();
            if (old == null || newGoal.isBlank() || !newGoal.equals(oldGoal)) {
                return new Checkpoint(goal == null ? "" : goal, "planning", UUID.randomUUID().toString(), "", 0, false);
            }
            return old;
        });
    }

    public static Checkpoint checkpointStarted(UUID companionId, String step, String result) {
        return PLANS.computeIfPresent(companionId, (id, old) ->
                new Checkpoint(old.goal(), step == null ? "unknown" : step, old.actionId(),
                        result == null ? "" : result, old.failures(), old.blocked()));
    }

    public static Checkpoint checkpointResult(UUID companionId, String step, String result, boolean failed) {
        return PLANS.computeIfPresent(companionId, (id, old) -> {
            String text = result == null ? "" : result;
            int failures = failed && text.equals(old.lastResult()) ? old.failures() + 1 : (failed ? 1 : 0);
            boolean blocked = failed && failures >= MAX_SAME_FAILURES;
            return new Checkpoint(old.goal(), step == null ? old.step() : step, old.actionId(), text, failures, blocked);
        });
    }

    public static boolean isBlocked(UUID companionId) {
        Checkpoint c = PLANS.get(companionId);
        return c != null && c.blocked();
    }

    public static String context(UUID companionId) {
        Checkpoint c = PLANS.get(companionId);
        if (c == null) return "checkpoint=none";
        return "checkpoint_goal=" + c.goal() + " step=" + c.step() + " action_id=" + c.actionId()
                + " last=" + c.lastResult() + " failures=" + c.failures() + " blocked=" + c.blocked();
    }

    public static void save(UUID companionId, CompoundTag tag) {
        Checkpoint c = PLANS.get(companionId);
        if (c == null) return;
        CompoundTag p = new CompoundTag();
        p.putString("Goal", c.goal());
        p.putString("Step", c.step());
        p.putString("ActionId", c.actionId());
        p.putString("LastResult", c.lastResult());
        p.putInt("Failures", c.failures());
        p.putBoolean("Blocked", c.blocked());
        tag.put("NPCMindPlan", p);
    }

    public static void load(UUID companionId, CompoundTag tag) {
        if (!tag.contains("NPCMindPlan", 10)) return;
        CompoundTag p = tag.getCompound("NPCMindPlan");
        PLANS.put(companionId, new Checkpoint(
                p.getString("Goal"), p.getString("Step"), p.getString("ActionId"),
                p.getString("LastResult"), p.getInt("Failures"), p.getBoolean("Blocked")));
    }

    public static void clear(UUID companionId) {
        PLANS.remove(companionId);
    }
}
