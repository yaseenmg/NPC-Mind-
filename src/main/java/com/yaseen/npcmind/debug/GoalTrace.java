package com.yaseen.npcmind.debug;

import com.yaseen.npcmind.entity.CompanionEntity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.yaseen.npcmind.api.Internal;

/**
 * Change-driven goal trace for NPC Mind's in-game diagnostics.
 *
 * <p>The kernel reports the companion's current decision phase here every tick. An entry is
 * only stored when the {@code state|detail} signature changes, so the trace shows exactly the
 * transitions a companion went through (e.g. {@code survival_guard -> combat -> work}) without
 * spamming one line per tick. The last 8 transitions are kept in memory for the
 * {@code inspect_ai} tool; nothing is written to disk from here.</p>
 */
@Internal
public final class GoalTrace {
    private GoalTrace() {}

    private static final int CAPACITY = 8;
    private record Entry(long gameTime, String state, String detail) {}
    private static final Map<UUID, Deque<Entry>> TRACE = new ConcurrentHashMap<>();
    private static final Map<UUID, String> LAST_SIG = new ConcurrentHashMap<>();

    /** Report the current phase; stored only when the signature actually changes. */
    public static void observe(CompanionEntity c, String state, String detail) {
        if (c == null || c.level().isClientSide() || state == null || state.isBlank()) return;
        String sig = state + "|" + (detail == null ? "" : detail);
        String prev = LAST_SIG.put(c.getUUID(), sig);
        if (sig.equals(prev)) return;
        Deque<Entry> queue = TRACE.computeIfAbsent(c.getUUID(), k -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(new Entry(c.level().getGameTime(), state, detail == null ? "" : detail));
            while (queue.size() > CAPACITY) queue.removeFirst();
        }
    }

    /** Recent transitions, oldest first; empty string when nothing was observed yet. */
    public static String dump(CompanionEntity c) {
        if (c == null) return "";
        Deque<Entry> queue = TRACE.get(c.getUUID());
        if (queue == null || queue.isEmpty()) return "no_transitions";
        StringBuilder b = new StringBuilder();
        synchronized (queue) {
            for (Entry e : queue) {
                if (b.length() > 0) b.append(" -> ");
                b.append('t').append(e.gameTime()).append(':').append(e.state());
                if (!e.detail().isBlank()) b.append('(').append(e.detail()).append(')');
            }
        }
        return b.toString();
    }

    /** Called from CompanionCleanup.purge so traces never leak per UUID. */
    public static void clear(UUID id) {
        if (id == null) return;
        TRACE.remove(id);
        LAST_SIG.remove(id);
    }
}
