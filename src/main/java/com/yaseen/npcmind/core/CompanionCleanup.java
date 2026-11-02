package com.yaseen.npcmind.core;

import com.yaseen.npcmind.advanced.AdaptiveCombatController;
import com.yaseen.npcmind.advanced.BodyguardController;
import com.yaseen.npcmind.advanced.EmergencyController;
import com.yaseen.npcmind.advanced.ExplorationController;
import com.yaseen.npcmind.advanced.StructureTravelController;
import com.yaseen.npcmind.advanced.ThreatMonitor;
import com.yaseen.npcmind.agent.AgentEngine;
import com.yaseen.npcmind.agent.AgentMemory;
import com.yaseen.npcmind.agent.PlanExecutionController;
import com.yaseen.npcmind.autonomy.AutonomyActionRouter;
import com.yaseen.npcmind.autonomy.AutonomyController;
import com.yaseen.npcmind.autonomy.NavigationArbiter;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.task.TaskManager;
import com.yaseen.npcmind.tool.impl.StructureSearchTool;

import java.util.UUID;
import com.yaseen.npcmind.api.Internal;

/**
 * Single place that releases every static per-companion map when an NPC dies or is removed.
 *
 * <p>Before this existed, a dozen controllers kept {@code Map<UUID, ...>} entries forever. On a
 * server that spawns and kills companions over time that is an unbounded leak, and stale entries
 * also made a respawned companion inherit the previous one's "last emergency tick", exploration
 * flag, travel target and planner future. One purge call from the entity lifecycle fixes both.
 */
@Internal
public final class CompanionCleanup {

    public static void purge(CompanionEntity c) {
        if (c == null) return;
        purge(c.getUUID());
    }

    public static void purge(UUID id) {
        if (id == null) return;
        // Tasks & navigation
        TaskManager.purge(id);
        NavigationArbiter.release(id);
        // Advanced controllers
        AdaptiveCombatController.clear(id);
        BodyguardController.clear(id);
        EmergencyController.clear(id);
        ExplorationController.clear(id);
        StructureTravelController.clear(id);
        ThreatMonitor.clear(id);
        StructureSearchTool.clear(id);
        com.yaseen.npcmind.tool.impl.CombatTargetTool.clearHunt(id);
        com.yaseen.npcmind.tool.impl.CraftItemTool.clearPending(id);
        com.yaseen.npcmind.advanced.SleepController.releaseClaims(id);
        com.yaseen.npcmind.advanced.InventoryIntelligence.clear(id);
        // Autonomy
        AutonomyController.clear(id);
        AutonomyActionRouter.clear(id);
        // Agent / planning / memory
        AgentEngine.forget(id);
        PlanExecutionController.clear(id);
        AgentMemory.clear(id);
        ActionJournal.clear(id);
        SkillLibrary.clear(id);
        // Kernel threat-scan cache + entity-level rate limits + the live-companion directory
        // (kept in sync so hasLiveCompanion never resolves a dead entity).
        CompanionKernel.clearScanCache(id);
        CompanionEntity.clearOfflineLogRateLimit(id);
        com.yaseen.npcmind.state.CompanionRespawnController.unregisterLiveCompanion(id);
        // Shared companion-assistance systems (own implementations)
        com.yaseen.npcmind.autonomy.CompanionWorkBudget.purge(id);
        com.yaseen.npcmind.advanced.CompanionAlertManager.clear(id);
        com.yaseen.npcmind.debug.GoalTrace.clear(id);
    }

    private CompanionCleanup() {}
}
