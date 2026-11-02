package com.yaseen.npcmind.core;

import com.yaseen.npcmind.NPCMind;
import com.yaseen.npcmind.debug.AuditLog;
import com.yaseen.npcmind.debug.Trace;
import com.yaseen.npcmind.entity.CompanionEntity;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.yaseen.npcmind.api.Internal;

/** One authoritative journal for actions, outcomes and repeated-failure detection. */
@Internal
public final class ActionJournal {
    public record Entry(String actionId, String tool, String args, String result, boolean failed, long tick) {}
    private static final int MAX = 24;
    private static final Map<UUID, Deque<Entry>> LOG = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> SAME_FAILURES = new ConcurrentHashMap<>();
    private static final Map<UUID, String> LAST_FAILURE = new ConcurrentHashMap<>();

    public static String begin(String tool, String args) { return UUID.randomUUID().toString(); }

    /** Drops all journal state for a companion that died or was removed (leak fix). */
    public static void clear(UUID id) {
        if (id == null) return;
        LOG.remove(id);
        SAME_FAILURES.remove(id);
        LAST_FAILURE.remove(id);
    }

    public static void finish(CompanionEntity c, String actionId, String tool, String args, String result) {
        boolean failed = result == null || result.startsWith("FAILED") || result.startsWith("ERROR")
                || result.startsWith("DENIED") || result.startsWith("TOOL_ERROR") || result.startsWith("TIMEOUT");
        LOG.computeIfAbsent(c.getUUID(), k -> new ConcurrentLinkedDeque<>()).addLast(new Entry(actionId, tool, args, result, failed, c.tickCount));
        AuditLog.tool(c.getUUID(), actionId, "FINISH", tool, Trace.clip(args, 1200), Trace.clip(result, 2500));
        while (LOG.get(c.getUUID()).size() > MAX) LOG.get(c.getUUID()).removeFirst();
        if (failed) {
            String fp = tool + "|" + args + "|" + result;
            int count = fp.equals(LAST_FAILURE.get(c.getUUID())) ? SAME_FAILURES.getOrDefault(c.getUUID(), 0) + 1 : 1;
            LAST_FAILURE.put(c.getUUID(), fp);
            SAME_FAILURES.put(c.getUUID(), count);
            if (count >= 2) NPCMind.LOG.warn("[NPCMind] repeated failure companion={} tool={} count={} result={}", c.getUUID(), tool, count, result);
        } else {
            SAME_FAILURES.remove(c.getUUID());
            LAST_FAILURE.remove(c.getUUID());
        }
    }

    public static int repeatedFailures(UUID id) { return SAME_FAILURES.getOrDefault(id, 0); }
    public static String recent(UUID id) {
        Deque<Entry> q = LOG.get(id);
        if (q == null || q.isEmpty()) return "none";
        StringBuilder b = new StringBuilder();
        for (Entry e : q) b.append(e.tool()).append("=").append(e.result()).append("; ");
        return b.length() > 1800 ? b.substring(b.length() - 1800) : b.toString();
    }
    private ActionJournal() {}
}
