package com.yaseen.npcmind.agent;

import com.yaseen.npcmind.NPCMind;
import com.yaseen.npcmind.debug.AuditLog;
import com.yaseen.npcmind.debug.Trace;
import com.yaseen.npcmind.core.*;
import com.yaseen.npcmind.core.ServerThreadGuard;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.task.TaskManager;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.ToolRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import com.yaseen.npcmind.api.Internal;

/**
 * Local command orchestrator. Minecraft state, command routing, task execution and verification
 * remain deterministic and server-side; this class never contacts a remote AI service.
 */
@Internal
public final class AgentEngine {
    // Each accepted command may wait on server-thread handoffs for minutes. Keep both worker
    // count and backlog bounded; never use CallerRunsPolicy because start() may be called from
    // the server thread and running the blocking loop there would stall ticks.
    private static final int MAX_WORKERS = 4;
    private static final int MAX_QUEUED_COMMANDS = 64;
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static final ThreadPoolExecutor EXECUTOR = BoundedWorkerPool.create(
            MAX_WORKERS, MAX_QUEUED_COMMANDS, r -> {
                Thread t = new Thread(r, "npcmind-brain-" + THREAD_IDS.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
    private static final Map<UUID, Future<?>> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> GENERATION = new ConcurrentHashMap<>();
    /** This engine is deliberately local-only; there is no remote provider state. */

    public static boolean isBusy(UUID id) { Future<?> f = ACTIVE.get(id); return f != null && !f.isDone(); }

    /** Cancels the running planner loop and drops every per-companion entry (leak fix). */
    public static void forget(UUID id) {
        if (id == null) return;
        Future<?> f = ACTIVE.remove(id);
        if (f != null && !f.isDone()) {
            f.cancel(true);
            if (f instanceof Runnable runnable) EXECUTOR.remove(runnable);
        }
        // Keep a monotonically increasing epoch: removing it allowed a quickly respawned UUID
        // (or a test harness reusing one) to reuse generation=1 and admit stale scheduled work.
        GENERATION.merge(id, 1L, Long::sum);
    }

    public static void runAsync(CompanionEntity c, ServerPlayer p, String message) { start(c, p, message); }

    private static void start(CompanionEntity c, ServerPlayer p, String message) {
        if (c == null || p == null || message == null || message.isBlank()
                || !ServerThreadGuard.isOnServerThread(c) || c.level() != p.level()) return;
        UUID id = c.getUUID();
        Future<?> old = ACTIVE.get(id);
        if (old != null && !old.isDone()) {
            GENERATION.merge(id, 1L, Long::sum);
            old.cancel(true);
            if (old instanceof Runnable runnable) EXECUTOR.remove(runnable);
            TaskManager.cancelCurrentTask(c);
        }
        long generation = GENERATION.merge(id, 1L, Long::sum);
        MinecraftServer server = p.server;
        UUID pid = p.getUUID();
        UUID cid = c.getUUID();
        PlanExecutionController.begin(cid, message);
        FutureTask<Void> task = new FutureTask<>(() -> {
            loop(server, pid, cid, message, generation);
            return null;
        }) {
            @Override protected void done() { ACTIVE.remove(cid, this); }
        };
        ACTIVE.put(cid, task);
        try {
            EXECUTOR.execute(task);
        } catch (RejectedExecutionException saturated) {
            task.cancel(false); // FutureTask.done() conditionally clears only this entry.
            if (GENERATION.getOrDefault(cid, generation) == generation) {
                PlanExecutionController.checkpointResult(cid, "worker_queue", "FAILED worker_capacity", true);
            }
            NPCMind.LOG.warn("[NPCMind] local command rejected: worker capacity reached ({} workers, {} queued)",
                    MAX_WORKERS, MAX_QUEUED_COMMANDS);
            deliver(server, pid, cid, "The local command queue is busy. Nothing was started; please retry shortly.", false);
        }
    }

    private static void loop(MinecraftServer server, UUID pid, UUID cid, String original, long generation) {
        try {
            if (cancelled(cid, generation) || !isEntityAliveOnServer(server, pid, cid)) return;

            // The repeated-failure lease only lives while the goal text is unchanged; a new
            // instruction calls begin() with a different goal and resets the checkpoint.
            if (PlanExecutionController.isBlocked(cid)) {
                AuditLog.event("PLAN", "BLOCKED", cid, PlanExecutionController.context(cid));
                deliver(server, pid, cid, "I stopped working on this because the same step kept failing. "
                        + "Rephrase the request or change the target and I will try a fresh approach.", false);
                return;
            }

            // Resolve a single local command before collecting any world context. The previous
            // obsolete planner-loop shell iterated up to eight times even though the local router only
            // produces one executable action; after a missing tool it retried with a null route.
            ToolSelectionRouter.Route routed = ToolSelectionRouter.first(original);
            if (routed == null) {
                AuditLog.event("LOCAL_ROUTER", "NO_MATCH", cid, "goal=" + Trace.clip(original, 800));
                deliver(server, pid, cid, ToolSelectionRouter.unsupportedResponse(original), false);
                return;
            }

            String name = routed.tool();
            String args = routed.arguments();
            AITool tool = ToolRegistry.get(name);
            AuditLog.decision(cid, original, name, "deterministic local capability router: " + routed.reason());
            if (tool == null) {
                deliver(server, pid, cid, "I don't have a registered local tool for that action.", false);
                return;
            }

            WorldSnapshot before = captureOnServer(server, pid, cid);
            if (before == null) {
                deliver(server, pid, cid, "I lost track of the world state. Please try again.", false);
                return;
            }
            String actionId = ActionJournal.begin(name, args);
            PlanExecutionController.checkpointStarted(cid, name, "STARTED action=" + actionId);
            AuditLog.tool(cid, actionId, "START", name, Trace.clip(args, 1200), null);
            AuditLog.world(cid, "BEFORE", Trace.clip(beforeSummary(before), 2500));

            String result = execute(server, pid, cid, tool, args, generation, actionId);
            if (result == null) result = "TOOL_ERROR null_result";
            boolean asynchronousStart = false;
            if (result.startsWith("STARTED")) {
                // TaskManager-owned work advertises its action_id. Controller-owned work
                // (combat, structure discovery, furnace jobs) must not wait on an unrelated task.
                if (result.startsWith("STARTED action_id=") && TaskManager.isActive(cid)) {
                    deliver(server, pid, cid, acknowledgement(name), false);
                    result = waitForTask(server, pid, cid, generation, 180000L);
                    // A long build/mine legitimately outlives the 3-minute wait. It used to be
                    // reported as "TIMEOUT" -> CriticEngine verdict failure -> "I couldn't complete
                    // that action" while the task kept running, so a player retry queued duplicate
                    // work. Treat the timeout as an accepted async action instead.
                    if (result.startsWith("TIMEOUT")) {
                        asynchronousStart = true;
                        result = "STILL_RUNNING task_execution; the work continues in the background, do not retry";
                    }
                } else {
                    asynchronousStart = true;
                }
            } else if (result.startsWith("PENDING")) {
                // A server-thread operation exceeded the synchronous response budget after it
                // had begun. Its late-result callback will report the actual outcome; do not
                // mark it as a normal tool failure or encourage a duplicate retry.
                asynchronousStart = true;
            }

            WorldSnapshot after = captureOnServer(server, pid, cid);
            if (after != null) AuditLog.world(cid, "AFTER", Trace.clip(afterSummary(after), 2500));
            CriticEngine.Verdict verdict = asynchronousStart
                    ? new CriticEngine.Verdict(true, true, false, "accepted_async_action")
                    : (after == null ? new CriticEngine.Verdict(false, false, true, "entity_missing")
                    : CriticEngine.evaluate(null, before, after, result));
            String trail = finishAndBuildTrail(server, pid, cid, actionId, name, args, result, verdict);
            if (trail == null) return;
            PlanExecutionController.checkpointResult(cid, name, result, !verdict.success());
            if (!verdict.success() && ActionJournal.repeatedFailures(cid) >= 2) {
                deliver(server, pid, cid, "I couldn't finish that: " + verdict.reason()
                        + ". I've stopped so I don't repeat the same mistake.", false);
                return;
            }

            String summary = asynchronousStart ? "Started: " + result
                    : (result.startsWith("SUCCESS") || result.startsWith("PARTIAL") || result.startsWith("RESULT")
                    ? "Result: " + result : "I couldn't complete that action: " + result);
            deliver(server, pid, cid, sanitize(summary), false);
        } catch (Throwable t) {
            AuditLog.error(cid, "local_command", t);
            NPCMind.LOG.error("[NPCMind] local command execution failed", t);
            deliver(server, pid, cid, "Something went wrong while I was working: "
                    + t.getClass().getSimpleName() + ".", false);
        }
    }

    private static String execute(MinecraftServer server, UUID pid, UUID cid, AITool tool, String args, long generation, String actionId) {
        CompletableFuture<String> result = new CompletableFuture<>();
        AtomicBoolean started = new AtomicBoolean(false);
        server.execute(() -> {
            // Claim execution before checking completion. This closes the race where a timeout
            // cancels an enqueued action between the first check and started.set(true).
            started.set(true);
            if (result.isDone()) return;
            try {
                if (GENERATION.getOrDefault(cid, generation) != generation) { result.complete("TOOL_ERROR stale_generation"); return; }
                ServerPlayer p = server.getPlayerList().getPlayer(pid);
                CompanionEntity c = find(server, cid);
                if (p == null || c == null || !c.isAlive()) { result.complete("TOOL_ERROR entity_offline"); return; }
                AgentToolRuntime.Result gate = AgentToolRuntime.invoke(c, p, tool.name(), args, actionId);
                result.complete(gate.result());
            } catch (Throwable t) {
                NPCMind.LOG.error("[NPCMind] tool {} crashed", tool.name(), t);
                result.complete("TOOL_ERROR " + t.getClass().getSimpleName());
            }
        });
        try {
            // 10 seconds was too short for modded servers under load and caused false failures.
            return result.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!started.get()) result.cancel(false);
            return "TOOL_ERROR interrupted";
        } catch (ExecutionException e) {
            NPCMind.LOG.error("[NPCMind] tool {} execution failed", tool.name(), e.getCause());
            return "TOOL_ERROR " + (e.getCause() == null ? "ExecutionException" : e.getCause().getClass().getSimpleName());
        } catch (TimeoutException e) {
            if (!started.get()) {
                result.cancel(false);
                return "TOOL_ERROR server_queue_timeout_cancelled_before_execution";
            }
            // Minecraft world operations must not be interrupted from a worker thread once they
            // have begun on the server thread. Make the uncertainty explicit and never retry this
            // action automatically (which could duplicate a placement/craft).
            NPCMind.LOG.warn("[NPCMind] tool {} exceeded 30s after starting for companion {}; no automatic retry", tool.name(), cid);
            result.whenComplete((lateResult, lateError) -> server.execute(() -> {
                if (lateError != null) {
                    NPCMind.LOG.error("[NPCMind] late tool result failed for {} companion {}", tool.name(), cid, lateError);
                    AuditLog.event("LATE_TOOL_RESULT", "ERROR", cid,
                            "tool=" + tool.name() + " action_id=" + actionId + " error=" + lateError.getClass().getSimpleName());
                    deliver(server, pid, cid, "The delayed " + tool.name() + " operation ended with an internal error. Check the NPC Mind log before retrying.", false);
                } else if (lateResult != null) {
                    AuditLog.event("LATE_TOOL_RESULT", "RECORDED", cid,
                            "tool=" + tool.name() + " action_id=" + actionId + " result=" + Trace.clip(lateResult, 1200));
                    deliver(server, pid, cid, "Delayed result from " + tool.name() + ": " + sanitize(lateResult), false);
                }
            }));
            return "PENDING execution_started_waiting_for_result action_id=" + actionId
                    + " tool=" + tool.name() + "; no automatic retry was scheduled";
        }
    }

    private static String waitForTask(MinecraftServer server, UUID pid, UUID cid, long generation, long timeout) {
        long end = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < end) {
            if (cancelled(cid, generation)) return "TOOL_ERROR cancelled";
            CompletableFuture<String> f = new CompletableFuture<>();
            server.execute(() -> {
                CompanionEntity c = find(server, cid);
                f.complete(c == null ? "TOOL_ERROR entity_offline" : (TaskManager.isActive(c) ? TaskManager.status(c) : Optional.ofNullable(TaskManager.takeLastResult(c)).orElse("FAILED missing_task_result")));
            });
            try {
                String s = f.get(700, TimeUnit.MILLISECONDS);
                if (!s.startsWith("RUNNING") && !s.startsWith("STARTED") && !s.startsWith("action_id=")) return s;
            } catch (TimeoutException pollTimeout) {
                // The server tick queue can naturally need longer than one polling interval.
                // This is not an execution failure and is deliberately not logged per poll.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return "TOOL_ERROR interrupted";
            } catch (java.util.concurrent.ExecutionException pollFailure) {
                NPCMind.LOG.debug("[NPCMind] task status poll failed for companion {}", cid, pollFailure);
            }
            try { Thread.sleep(120); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return "TOOL_ERROR interrupted"; }
        }
        return "TIMEOUT task_execution";
    }

    private static WorldSnapshot captureOnServer(MinecraftServer server, UUID pid, UUID cid) {
        CompletableFuture<WorldSnapshot> f = new CompletableFuture<>();
        server.execute(() -> { ServerPlayer p = server.getPlayerList().getPlayer(pid); CompanionEntity c = find(server, cid); f.complete(c == null || p == null ? null : WorldSnapshot.capture(c, p)); });
        try {
            return f.get(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            NPCMind.LOG.debug("[NPCMind] snapshot capture interrupted for companion {}", cid);
            return null;
        } catch (ExecutionException | TimeoutException failure) {
            NPCMind.LOG.debug("[NPCMind] snapshot capture unavailable for companion {}", cid, failure);
            return null;
        }
    }

    private static String beforeSummary(WorldSnapshot s) { return snapshotSummary(s); }
    private static String afterSummary(WorldSnapshot s) { return snapshotSummary(s); }
    private static String snapshotSummary(WorldSnapshot s) {
        return "pos=" + s.position() + " | hp=" + s.health() + " | food=" + s.food()
                + " | sat=" + s.saturation() + " | inv_slots=" + s.inventoryItems()
                + " | inv_sig=" + s.inventorySignature() + " | hostiles=" + s.nearbyHostiles()
                + " | water=" + s.inWater() + " | fire=" + s.onFire() + " | lava=" + s.inLava()
                + " | target=" + s.hasTarget() + " | task=" + Trace.clip(s.taskStatus(), 500) + " | tick=" + s.tick();
    }

    private static boolean isEntityAliveOnServer(MinecraftServer server, UUID pid, UUID cid) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(pid);
            CompanionEntity c = find(server, cid);
            f.complete(p != null && c != null && c.isAlive());
        });
        try { return f.get(800, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        catch (Exception e) { return false; }
    }

    private static String finishAndBuildTrail(MinecraftServer server, UUID pid, UUID cid, String actionId, String name, String args, String result, CriticEngine.Verdict verdict) {
        CompletableFuture<String> f = new CompletableFuture<>();
        server.execute(() -> {
            try {
                CompanionEntity c = find(server, cid);
                if (c == null) { f.complete(null); return; }
                ActionJournal.finish(c, actionId, name, args, result);
                AuditLog.event("VERIFY", verdict.success() ? "SUCCESS" : "FAILED", cid,
                        "action_id=" + actionId + " | reason=" + verdict.reason() + " | progress=" + verdict.progress());
                c.getDecisions().record("agent_tool", name, verdict.success() ? "SUCCESS" : "FAILED:" + verdict.reason());
                if (verdict.success()) SkillLibrary.recordSuccess(c, name, verdict.reason());
                else SkillLibrary.recordFailure(c, name, verdict.reason());
                f.complete("step=" + name + " result=" + clip(result, 700)
                        + " critic=" + verdict.reason() + " progress=" + verdict.progress()
                        + " recent=" + ActionJournal.recent(cid)
                        + " skills=" + SkillLibrary.retrieve(c, name));
            } catch (Throwable t) {
                AuditLog.error(cid, "action_finalize", t);
                f.complete(null);
            }
        });
        try { return f.get(1500, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        catch (Exception e) { return null; }
    }

    private static boolean cancelled(UUID id, long generation) { return Thread.currentThread().isInterrupted() || GENERATION.getOrDefault(id, generation) != generation; }

    private static String acknowledgement(String name) { return switch (name) {
        case "move_to", "smart_navigate" -> "On my way.";
        case "follow_owner" -> "Following you.";
        case "mine_block", "gather_wood", "smart_vein_mining" -> "Mining now.";
        case "gather_resources" -> "Gathering resources.";
        case "build_pattern", "build_blueprint", "place_block" -> "Building now.";
        case "combat_target" -> "Engaging the target.";
        case "search_structure" -> "Searching for the structure.";
        case "cook_food", "smelt_item" -> "Cooking now.";
        case "farm_area", "till_and_plant" -> "Working the farm.";
        case "explore_mode" -> "Exploring.";
        case "bodyguard_mode" -> "Guarding you.";
        default -> "Working on it.";
    }; }

    /**
     * Sends the companion's final answer to the owner. This used to be an empty method, which is
     * why the NPC never replied to anything: the planner produced text and then threw it away.
     * The message is posted to the server thread because entity speech touches world state.
     */
    private static void deliver(MinecraftServer server, UUID pid, UUID cid, String text, boolean voice) {
        if (server == null) return;
        String clean = sanitize(text);
        if (clean.isBlank()) return;
        server.execute(() -> {
            CompanionEntity c = find(server, cid);
            if (c != null && c.isAlive()) {
                // say() routes through the host mod's CompanionSpeech sink when one is installed,
                // otherwise it falls back to a system chat message. That covers both cases here.
                c.say(clean);
            } else {
                ServerPlayer p = server.getPlayerList().getPlayer(pid);
                if (p != null) p.sendSystemMessage(net.minecraft.network.chat.Component.literal("[NPC] " + clean));
            }
        });
    }

    private static CompanionEntity find(MinecraftServer server, UUID id) { for (ServerLevel l : server.getAllLevels()) if (l.getEntity(id) instanceof CompanionEntity c) return c; return null; }
    private static String clip(String s, int n) { if (s == null) return ""; return s.length() <= n ? s : s.substring(0, n) + "\u2026"; }

    static String sanitize(String s) {
        if (s == null) return "";
        String t = s.replace('\u0000', ' ')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        // Drop immediately repeated sentences. The old regex collapsed every repeated *word*,
        // which mangled legitimate text ("very very fast" -> "very fast") and could not fix the
        // duplicated failure sentences the corrupted prompts produced.
        String[] parts = t.split("(?<=[.!?؟])\\s+");
        StringBuilder sb = new StringBuilder();
        String prev = null;
        for (String part : parts) {
            String k = part.trim();
            if (k.isEmpty()) continue;
            if (k.equalsIgnoreCase(prev)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(k);
            prev = k;
        }
        return sb.toString();
    }

    /** Report the actual local execution mode without probing any remote service. */
    public static void diagnose(ServerPlayer player) {
        if (!ServerThreadGuard.isOnServerThread(player)) return;
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "[NPC Mind] Local command engine active; no external API or server is used."));
    }

    /** No network warm-up is necessary for a local deterministic command engine. */
    public static void warmUp() { }

    private AgentEngine() {}
}
