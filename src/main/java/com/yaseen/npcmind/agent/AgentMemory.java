package com.yaseen.npcmind.agent;

import com.yaseen.npcmind.Config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.yaseen.npcmind.api.Internal;

/**
 * Bounded episodic memory plus scoped tool-failure memory. A failure belongs to a concrete
 * tool+arguments pair, not to the tool globally; otherwise two failed mining calls could
 * permanently disable the otherwise healthy inventory-inspection tool, for example.
 */
@Internal
public final class AgentMemory {
    public record Event(long time, String kind, String text) {}
    private record Failure(int count, long lastFailureAt) {}
    private static final long FAILURE_TTL_MS = 30_000L;
    private static final Map<UUID, ArrayDeque<Event>> EVENTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, Failure>> TOOL_FAILURES = new ConcurrentHashMap<>();

    public static void add(UUID id, String kind, String text) {
        if (id == null) return;
        ArrayDeque<Event> q = EVENTS.computeIfAbsent(id, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(new Event(System.currentTimeMillis(), kind, clip(text, 700)));
            while (q.size() > Config.MEMORY_LIMIT.get()) q.removeFirst();
        }
    }

    public static void failure(UUID id, String tool) { failure(id, tool, "{}"); }
    public static void failure(UUID id, String tool, String args) {
        if (id == null || tool == null) return;
        String key = key(tool, args);
        TOOL_FAILURES.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).compute(key, (k, old) -> {
            long now = System.currentTimeMillis();
            if (old == null || now - old.lastFailureAt() > FAILURE_TTL_MS) return new Failure(1, now);
            return new Failure(old.count() + 1, now);
        });
    }

    public static void success(UUID id, String tool) { success(id, tool, "{}"); }
    public static void success(UUID id, String tool, String args) {
        Map<String,Failure> m = TOOL_FAILURES.get(id);
        if (m == null || tool == null) return;
        m.remove(key(tool, args));
    }

    public static int failures(UUID id, String tool) { return failures(id, tool, "{}"); }
    public static int failures(UUID id, String tool, String args) {
        Map<String,Failure> m = TOOL_FAILURES.get(id);
        if (m == null || tool == null) return 0;
        Failure f = m.get(key(tool, args));
        if (f == null) return 0;
        if (System.currentTimeMillis() - f.lastFailureAt() > FAILURE_TTL_MS) { m.remove(key(tool, args)); return 0; }
        return f.count();
    }

    public static String summary(UUID id) {
        ArrayDeque<Event> q = EVENTS.get(id);
        if (q == null) return "memory=empty";
        StringBuilder b = new StringBuilder("memory:");
        synchronized (q) { for (Event e : q) b.append("\n- ").append(e.kind()).append(": ").append(e.text()); }
        return b.toString();
    }

    public static void clear(UUID id) { EVENTS.remove(id); TOOL_FAILURES.remove(id); }

    private static String key(String tool, String args) { return tool + "#" + sha256(args == null ? "{}" : args); }
    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(64);
            for (byte x : d) b.append(String.format(Locale.ROOT, "%02x", x));
            return b.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }
    private static String clip(String s, int n) { if (s == null) return ""; return s.length() <= n ? s : s.substring(0,n)+"…"; }
    private AgentMemory() {}
}
