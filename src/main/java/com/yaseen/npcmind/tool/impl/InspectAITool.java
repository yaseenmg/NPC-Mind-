package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.advanced.CompanionAlertManager;
import com.yaseen.npcmind.autonomy.CompanionWorkBudget;
import com.yaseen.npcmind.debug.GoalTrace;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;

/**
 * In-game diagnostics surface for one companion. Wraps data that already exists in NPC Mind:
 * the change-driven goal trace, task status, combat
 * target, work-budget load shedding and teammate alerts. Read-only; no world interaction.
 */
@Internal
public final class InspectAITool implements AITool {
    @Override public String name() { return "inspect_ai"; }
    @Override public String description() {
        return "Read the companion's live AI diagnostics: recent decision-phase transitions, " +
                "current task, combat target, server work-budget/load-shedding state and teammate alerts. " +
                "Useful after any 'why did he stop working' question. Read-only.";
    }
    @Override public String schema() { return "{}"; }

    @Override
    public String execute(CompanionEntity c, ServerPlayer p, String a) {
        if (c == null) return "FAILED companion_missing";
        StringBuilder b = new StringBuilder();
        b.append("AI-INSPECT t=").append(c.tickCount)
                .append(" hp=").append(String.format(java.util.Locale.ROOT, "%.1f", c.getHealth()))
                .append(" food=").append(c.getFoodLevel())
                .append(" autonomy=").append(c.getAutonomy().mode()).append('\n');
        b.append("trace=").append(GoalTrace.dump(c)).append('\n');
        b.append("task=").append(com.yaseen.npcmind.task.TaskManager.status(c)).append('\n');
        if (c.getTarget() != null && c.getTarget().isAlive()) {
            b.append("combatTarget=").append(c.getTarget().getType().builtInRegistryHolder().key().location())
                    .append(" hp=").append(String.format(java.util.Locale.ROOT, "%.1f", c.getTarget().getHealth())).append('\n');
        } else {
            b.append("combatTarget=none\n");
        }
        b.append("workBudget=").append(CompanionWorkBudget.status()).append('\n');
        b.append("alerts=").append(CompanionAlertManager.status());
        return b.toString();
    }
}
