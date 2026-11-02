package com.yaseen.npcmind.debug;

import com.yaseen.npcmind.NPCMind;
import java.util.UUID;
import com.yaseen.npcmind.api.Internal;

/** Central diagnostic tracer. Mirrors important traces to persistent NPCMind audit files. */
@Internal
public final class Trace {
    private Trace() {}
    public static volatile boolean ENABLED = true;

    public static void log(String area, String message) { log((UUID) null, area, message); }
    public static void log(UUID companion, String area, String message) {
        if (!ENABLED) return;
        NPCMind.LOG.info("[NPCMind][TRACE][{}] {}", area, message);
        AuditLog.event(area, "TRACE", companion, message);
    }
    public static void log(String area, String fmt, Object a) { log(area, fmt, new Object[]{a}); }
    public static void log(String area, String fmt, Object a, Object b) { log(area, fmt, new Object[]{a,b}); }
    public static void log(String area, String fmt, Object a, Object b, Object c) { log(area, fmt, new Object[]{a,b,c}); }
    public static void log(String area, String fmt, Object a, Object b, Object c, Object d) { log(area, fmt, new Object[]{a,b,c,d}); }
    private static void log(String area, String fmt, Object[] args) {
        if (!ENABLED) return;
        String msg = format(fmt, args);
        NPCMind.LOG.info("[NPCMind][TRACE][{}] {}", area, msg);
        AuditLog.event(area, "TRACE", null, msg);
    }
    private static String format(String fmt, Object[] args) {
        String s = fmt == null ? "" : fmt;
        for (Object a : args) s = s.replaceFirst("\\{}", java.util.regex.Matcher.quoteReplacement(String.valueOf(a)));
        return s;
    }
    public static String clip(String s) { return clip(s, 200); }
    public static String clip(String s, int max) {
        if (s == null) return "null";
        s = s.replace('\n',' ').replace('\r',' ');
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
