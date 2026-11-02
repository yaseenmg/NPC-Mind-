package com.yaseen.npcmind.advanced;

import java.util.LinkedHashMap;
import java.util.Map;
import com.yaseen.npcmind.api.Internal;

/** Small per-companion throttle for expensive, non-critical decisions and observation passes. */
@Internal
public final class AITickThrottle {
    private final Map<String, Integer> nextAllowedTick = new LinkedHashMap<>();

    /** Returns true once per interval for a key; callers must not throttle emergency reflexes. */
    public boolean due(int currentTick, String key, int intervalTicks) {
        if (key == null || key.isBlank()) return true;
        int interval = Math.max(1, Math.min(1200, intervalTicks));
        Integer next = nextAllowedTick.get(key);
        if (next != null && currentTick < next) return false;
        nextAllowedTick.put(key, currentTick + interval);
        if (nextAllowedTick.size() > 32) {
            // entry values are "next allowed" ticks, so a stale entry is at least one full
            // interval old plus the 2400-tick grace window; checking value + interval keeps
            // the eviction boundary exact instead of off by the entry's own interval.
            nextAllowedTick.entrySet().removeIf(entry -> currentTick - entry.getValue() > 2400 + interval);
            while (nextAllowedTick.size() > 32) nextAllowedTick.remove(nextAllowedTick.keySet().iterator().next());
        }
        return true;
    }

    public void reset(String key) { if (key != null) nextAllowedTick.remove(key); }
    public void clear() { nextAllowedTick.clear(); }
}
