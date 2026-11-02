package com.yaseen.npcmind.debug;

import com.yaseen.npcmind.NPCMind;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import com.yaseen.npcmind.api.Internal;

/** Persistent, grep-friendly audit trail for companion behaviour. */
@Internal
public final class AuditLog {
    public static volatile boolean ENABLED = true;
    public static volatile boolean WRITE_GLOBAL = true;
    public static volatile boolean WRITE_COMPANION = true;
    /** Soft upper bound on retained per-file locks; excess idle locks are pruned after unlock. */
    private static final int MAX_LOCKS = 256;
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private AuditLog() {}

    private static Path root() { return FMLPaths.GAMEDIR.get().resolve("logs").resolve("npcmind"); }

    public static void event(String area, String event, UUID companion, String message) {
        if (!ENABLED) return;
        String line = Instant.now() + " | area=" + safe(area) + " | event=" + safe(event)
                + (companion == null ? "" : " | companion=" + companion)
                + " | " + safe(message) + System.lineSeparator();
        if (WRITE_GLOBAL) append(root().resolve("companions.log"), line);
        if (WRITE_COMPANION && companion != null) append(root().resolve("companions").resolve(companion + ".log"), line);
    }

    public static void chat(UUID c, String direction, String message) { event("CHAT", direction, c, "message=\"" + message + "\""); }
    public static void decision(UUID c, String goal, String decision, String reason) {
        event("DECISION", "SELECT", c, "goal=\"" + goal + "\" | decision=\"" + decision + "\" | reason=\"" + reason + "\"");
    }
    public static void tool(UUID c, String actionId, String phase, String tool, String args, String result) {
        event("ACTION", phase, c, "action_id=" + actionId + " | tool=" + tool + " | args=" + args + (result == null ? "" : " | result=" + result));
    }
    public static void world(UUID c, String phase, String snapshot) { event("WORLD", phase, c, "snapshot=" + snapshot); }
    public static void error(UUID c, String where, Throwable t) { event("ERROR", where, c, "exception=" + t.getClass().getName() + " | message=\"" + t.getMessage() + "\""); }
    private static void append(Path file, String line) {
        ReentrantLock lock = LOCKS.computeIfAbsent(file, p -> new ReentrantLock());
        lock.lock();
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                w.write(line);
            }
        } catch (IOException e) {
            NPCMind.LOG.warn("[NPCMind] audit log write failed: {}", e.toString());
        } finally {
            lock.unlock();
            if (LOCKS.size() > MAX_LOCKS && !lock.isLocked() && !lock.hasQueuedThreads()) {
                LOCKS.remove(file, lock);
            }
        }
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace('\n',' ').replace('\r',' ').replace('|','/');
    }
}
