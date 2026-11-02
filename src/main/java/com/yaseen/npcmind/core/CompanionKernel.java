package com.yaseen.npcmind.core;

import com.yaseen.npcmind.advanced.AdaptiveCombatController;
import com.yaseen.npcmind.advanced.CompanionAlertManager;
import com.yaseen.npcmind.advanced.BodyguardController;
import com.yaseen.npcmind.advanced.EmergencyController;
import com.yaseen.npcmind.advanced.ExplorationController;
import com.yaseen.npcmind.advanced.SceneMemory;
import com.yaseen.npcmind.advanced.SurvivalGuard;
import com.yaseen.npcmind.advanced.SleepController;
import com.yaseen.npcmind.advanced.ThreatMonitor;
import com.yaseen.npcmind.advanced.WorldObservation;
import com.yaseen.npcmind.autonomy.AutonomyController;
import com.yaseen.npcmind.autonomy.NavigationArbiter;
import com.yaseen.npcmind.autonomy.DailyRoutineController;
import com.yaseen.npcmind.autonomy.SuggestionController;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.task.TaskManager;
import com.yaseen.npcmind.api.Internal;

/**
 * Single lifecycle owner for the companion's high-level control loop.
 *
 * <p>The old entity tick accumulated controllers over many releases. That made it easy for a
 * new controller to start writing navigation/targets without knowing what already owned the
 * companion. This kernel enforces an explicit order:</p>
 *
 * <pre>survival -> combat -> active work -> guard/explore -> autonomy -> observation</pre>
 *
 * <p>Long-running work is owned by TaskManager; only local task controllers move the entity.</p>
 */
@Internal
public final class CompanionKernel {
    private CompanionKernel() {}

    private static boolean hasImmediateThreat(CompanionEntity c) {
        if (c.getTarget() instanceof net.minecraft.world.entity.Mob targetMob && targetMob.getTarget() == c) return true;
        net.minecraft.world.entity.LivingEntity recent = c.getLastHurtByMob();
        // getLastHurtByMob is a historical reference and is not cleared just because combat ended.
        // Require a recent hurt timestamp as well as proximity, otherwise an old attacker can
        // repeatedly preempt building/mining for the rest of the companion's lifetime.
        int hurtAge = c.tickCount - c.getLastHurtByMobTimestamp();
        if (recent != null && recent.isAlive() && hurtAge >= 0 && hurtAge <= 100
                && c.distanceToSqr(recent) <= 256.0D) return true;
        // The three box scans below (creeper 7b, mobs 12b, owner 16b) ran on EVERY tick while
        // work was active. They only need a bounded reaction time: cache the combined result
        // for 10 ticks (0.5 s, same cadence family as SurvivalGuard). The O(1) checks above
        // stay uncached so an attacking mob or a fresh hit is seen on the same tick.
        return scanThreatsCached(c);
    }

    private static final java.util.Map<java.util.UUID, Long> SCAN_AT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Boolean> SCAN_RESULT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int SCAN_INTERVAL_TICKS = 10;

    private static boolean scanThreatsCached(CompanionEntity c) {
        Long at = SCAN_AT.get(c.getUUID());
        Boolean cached = SCAN_RESULT.get(c.getUUID());
        long age = at == null ? Long.MAX_VALUE : c.tickCount - at;
        if (cached != null && age >= 0 && age < SCAN_INTERVAL_TICKS) return cached;
        boolean result = scanThreats(c);
        SCAN_AT.put(c.getUUID(), (long) c.tickCount);
        SCAN_RESULT.put(c.getUUID(), result);
        return result;
    }

    private static boolean scanThreats(CompanionEntity c) {
        for (net.minecraft.world.entity.monster.Creeper creeper : c.level().getEntitiesOfClass(
                net.minecraft.world.entity.monster.Creeper.class, c.getBoundingBox().inflate(7.0D),
                cr -> cr.isAlive() && (cr.isIgnited() || cr.getSwelling(0.0F) > 0.1F))) return true;
        for (net.minecraft.world.entity.Mob mob : c.level().getEntitiesOfClass(
                net.minecraft.world.entity.Mob.class, c.getBoundingBox().inflate(12.0D),
                entity -> entity.isAlive() && entity.getTarget() == c)) return true;
        net.minecraft.world.entity.player.Player owner = c.getOwnerPlayer();
        if (owner != null && owner.level() == c.level() && owner.isAlive()) {
            for (net.minecraft.world.entity.Mob mob : c.level().getEntitiesOfClass(
                    net.minecraft.world.entity.Mob.class, owner.getBoundingBox().inflate(16.0D),
                    entity -> entity.isAlive() && entity.getTarget() == owner
                            && c.distanceToSqr(entity) <= 1024.0D)) return true;
        }
        return false;
    }

    /** Called from CompanionCleanup.purge so the threat-scan cache does not leak per UUID. */
    public static void clearScanCache(java.util.UUID id) {
        if (id == null) return;
        SCAN_AT.remove(id);
        SCAN_RESULT.remove(id);
    }

    public static boolean tick(CompanionEntity c) {
        if (c == null || c.level().isClientSide() || !c.isAlive()) return false;
        NavigationArbiter.tick(c);

        if (SurvivalGuard.tick(c, c.survivalState())) {
            com.yaseen.npcmind.debug.GoalTrace.observe(c, "survival_guard", "");
            return true;
        }

        boolean workActive = TaskManager.isActive(c);
        // Do not let the general combat scan interrupt every build/mine/move just because a hostile
        // happens to be nearby. Only an actual threat (an entity targeting the companion, a nearby
        // recent attacker, a fusing creeper, or a mob attacking the owner) may preempt a task. Explicit
        // player-directed combat still wins because its tool sets friendlyCombat and cancels work.
        if (!workActive || c.isFriendlyCombat() || hasImmediateThreat(c)) {
            AdaptiveCombatController.tick(c);
            if (c.getTarget() != null && c.getTarget().isAlive()) {
                // A verified threat now owns movement. Terminate the work action explicitly instead of
                // leaving a mining/building queue stalled under the higher-priority combat path.
                if (TaskManager.isActive(c)) {
                    TaskManager.cancelForReflex(c, "combat_preemption target=" + c.getTarget().getUUID());
                }
                com.yaseen.npcmind.debug.GoalTrace.observe(c, "combat",
                        c.getTarget().getType().builtInRegistryHolder().key().location().getPath());
                return true;
            }
        }

        // A running gather/move/mine/build plan is authoritative. A pending craft also owns the
        // next decision even during the short gap between mining, drop pickup, smelting and recipe
        // re-evaluation; autonomy must not steal navigation in that gap.
        if (TaskManager.isActive(c) || com.yaseen.npcmind.tool.impl.CraftItemTool.hasPending(c.getUUID())) {
            // Stuck-queue review fix: a long task stops this companion from reaching the
            // tryLease paths below, which used to leave its work-budget rotation entry pinned
            // and starve other companions. Give the slot back for the whole task duration;
            // the TTL heartbeat would also reclaim it, this just does it immediately.
            com.yaseen.npcmind.autonomy.CompanionWorkBudget.release(c.getUUID());
            com.yaseen.npcmind.debug.GoalTrace.observe(c, "work", "active");
            return true;
        }

        // Teammate-alert assistance: an idle companion may help a nearby teammate that is
        // under attack. Alert state is pruned on
        // read, bounded by TTL and radius, and never sourced from the companion's own alert.
        if (!c.isFriendlyCombat()) {
            net.minecraft.world.entity.LivingEntity assist = CompanionAlertManager.pickAssistTarget(c);
            if (assist != null) {
                TaskManager.cancelCurrentTask(c);
                c.setTarget(assist);
                c.getLookControl().setLookAt(assist, 30.0F, 30.0F);
                // Melee engagement needs <= 16 blocks; beyond that walk toward the fight first
                // instead of locking a target the attack goal cannot yet reach.
                if (c.distanceToSqr(assist) > 256.0D) {
                    NavigationArbiter.request(c, assist, "assist", 500, 1.2D);
                }
                com.yaseen.npcmind.debug.GoalTrace.observe(c, "assist_teammate",
                        assist.getType().builtInRegistryHolder().key().location().getPath());
                return true;
            }
        }

        BodyguardController.tick(c);
        if (c.getTarget() != null && c.getTarget().isAlive()) return true;

        EmergencyController.tick(c);
        if (c.getTarget() != null && c.getTarget().isAlive()) return true;

        // Nightly bed-seeking is lower priority than danger, combat and explicit work.
        if (SleepController.tick(c, c.sleepState())) {
            com.yaseen.npcmind.debug.GoalTrace.observe(c, "sleep", "");
            return true;
        }

        // Exploration ticks in EXPLORER mode AND whenever an explicit exploration window is active
        // (explore_mode tool or the search_structure discovery period). Without the second check
        // those two features were dead in every default (FOLLOW) mode companion.
        if (c.getAutonomy().mode() == com.yaseen.npcmind.autonomy.AutonomyState.Mode.EXPLORER
                || ExplorationController.active(c)) {
            // Free exploration issues navigation requests: it participates in the server-wide
            // work budget so many companions cannot pathfind in the same tick under load.
            if (com.yaseen.npcmind.autonomy.CompanionWorkBudget.tryLease(c, "exploration")) {
                try {
                    ExplorationController.tick(c);
                } finally {
                    com.yaseen.npcmind.autonomy.CompanionWorkBudget.release(c);
                }
            }
            if (TaskManager.isActive(c)) return true;
        }

        // Owner-facing creeper early warning (previously wired nowhere, so the class was dead code).
        net.minecraft.world.entity.player.Player warnOwner = c.getOwnerPlayer();
        if (warnOwner instanceof net.minecraft.server.level.ServerPlayer serverWarnOwner) {
            ThreatMonitor.tick(serverWarnOwner, c);
        }

        // Throttle only non-critical background decisions. Survival and combat remain full-rate.
        // observation/scene_memory/daily_routine/suggestions already gate themselves internally
        // (tickCount % 40/100/200), so an outer throttle only added dead call overhead and, with
        // the old phase-locked intervals, could starve them entirely.
        // The autonomy decision loop also acquires the server-wide work budget: it is the
        // driver of most speculative navigation and block scanning, so under MSPT pressure it
        // sheds first while survival/combat/work stay full-rate.
        if (c.aiTickThrottle().due(c.tickCount, "autonomy", 5)
                && com.yaseen.npcmind.autonomy.CompanionWorkBudget.tryLease(c, "autonomy")) {
            try {
                AutonomyController.tick(c);
            } finally {
                com.yaseen.npcmind.autonomy.CompanionWorkBudget.release(c);
            }
        } else if (c.aiTickThrottle().due(c.tickCount, "autonomy", 5)) {
            com.yaseen.npcmind.debug.GoalTrace.observe(c, "work_budget_wait", "");
        }
        WorldObservation.tick(c);
        SceneMemory.tick(c);
        DailyRoutineController.tick(c);
        SuggestionController.tick(c);
        return true;
    }
}
