package com.yaseen.npcmind.autonomy;

import com.yaseen.npcmind.entity.CompanionEntity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.yaseen.npcmind.api.Internal;

/**
 * Server-wide AI work budget that limits expensive autonomy decisions and rotates fairly
 * among companions requesting work.
 *
 * <p>Problem: every companion runs its autonomy/explore decision loop on the server thread.
 * With many companions the pathfinding and block-scanning work of several companions can land
 * in the same tick and produce a visible MSPT spike.</p>
 *
 * <p>This budget bounds how many companions may start an <b>expensive autonomy decision</b> in
 * the same server tick, rotates fairly between the companions that want to work, and shrinks
 * the allowance automatically when the server is under load (MSPT based load shedding).</p>
 *
 * <p><b>Stuck-queue safety (review fix):</b> the rotation is a queue of companions that
 * <em>want</em> to work, not a permanent reservation. Every request refreshes a per-entry
 * {@code lastSeenTick} heartbeat; an entry that has not asked for a lease for
 * {@link #ENTRY_TTL_TICKS} is evicted automatically. A companion that stops requesting
 * (autonomy mode changed, a long task started, exploration disabled, entity removed without
 * going through the cleanup path) therefore can never pin the head of the queue and starve
 * the others: within one TTL window the next waiter automatically becomes the head. Waiting
 * for a turn and holding a grant are deliberately separate states — a temporary rejection
 * (capacity or not-your-turn) never leaves a lasting claim behind.</p>
 *
 * <p>Explicitly NOT gated by this budget (unchanged full-rate behavior):
 * SurvivalGuard, AdaptiveCombatController, EmergencyController, TaskManager work ticks,
 * SleepController and ThreatMonitor. Only the optional background drivers (autonomy routing
 * and free exploration) acquire a permit here.</p>
 */
@Internal
public final class CompanionWorkBudget {
    private CompanionWorkBudget() {}

    /** Fair rotation of companions that currently want to run an expensive decision. */
    private static final Deque<RotationQueue.Entry> ROTATION = new ArrayDeque<>();
    private static final Set<UUID> IN_ROTATION = ConcurrentHashMap.newKeySet();

    /**
     * A queued companion that has not asked for a lease for this many ticks is considered
     * inactive and is evicted. Generous against the 5-tick autonomy throttle and the 1-tick
     * exploration cadence, short enough that a vanished companion cannot starve the queue
     * for longer than 3 seconds.
     */
    static final long ENTRY_TTL_TICKS = 60L;

    private static volatile long tickCursor = Long.MIN_VALUE;
    private static volatile int grantedThisTick = 0;
    private static volatile int currentCapacity = 3;
    private static volatile String lastShed = "healthy";

    /**
     * Try to acquire this tick's permission to run one expensive decision.
     *
     * @return true when granted; the caller MUST call {@link #release} in a finally block.
     */
    public static boolean tryLease(CompanionEntity c, String purpose) {
        if (c == null || c.level().isClientSide()) return false;
        var server = c.level().getServer();
        if (server == null) return false;
        long now = c.level().getGameTime();
        float mspt = server.getAverageTickTime();
        int capacity = capacityFor(mspt);
        currentCapacity = capacity;
        if (capacity <= 0) {
            lastShed = "shedding_mspt=" + String.format(java.util.Locale.ROOT, "%.1f", mspt);
            return false;
        }
        synchronized (LOCK) {
            if (now != tickCursor) { tickCursor = now; grantedThisTick = 0; evictStaleLocked(now); }
            if (grantedThisTick >= capacity) return false;
            UUID id = c.getUUID();
            touchLocked(id, now);
            // Fairness: a companion may only run when it is at the head of the rotation (or
            // the queue effectively only contains it). The head advances when it releases or
            // when its entry expires — an absent requester can no longer pin the head.
            UUID head = ROTATION.peekFirst() == null ? null : ROTATION.peekFirst().id();
            if (head != null && !head.equals(id) && ROTATION.size() > 1) return false;
            grantedThisTick++;
            return true;
        }
    }

    /** Release the rotation slot after the decision finished. */
    public static void release(CompanionEntity c) {
        if (c == null) return;
        release(c.getUUID());
    }

    public static void release(UUID id) {
        if (id == null) return;
        synchronized (LOCK) {
            if (IN_ROTATION.remove(id)) removeLocked(id);
        }
    }

    /** Called from CompanionCleanup.purge so dead companions never block the rotation. */
    public static void purge(UUID id) { release(id); }

    private static final Object LOCK = new Object();
    private static final int MAX_QUEUE = 32;

    /** Add or heartbeat the requester's entry (keeps its position; never reorders the queue). */
    private static void touchLocked(UUID id, long now) {
        if (IN_ROTATION.contains(id)) {
            // Heartbeat in place: rebuild the deque so the entry keeps its FIFO position.
            ArrayDeque<RotationQueue.Entry> next = new ArrayDeque<>(ROTATION.size());
            for (RotationQueue.Entry e : ROTATION) {
                next.addLast(e.id().equals(id) ? e.withLastSeen(now) : e);
            }
            ROTATION.clear();
            ROTATION.addAll(next);
            return;
        }
        if (ROTATION.size() >= MAX_QUEUE) return; // bounded: retry next tick
        ROTATION.addLast(new RotationQueue.Entry(id, now));
        IN_ROTATION.add(id);
    }

    private static void removeLocked(UUID id) {
        for (Iterator<RotationQueue.Entry> it = ROTATION.iterator(); it.hasNext(); ) {
            if (it.next().id().equals(id)) { it.remove(); return; }
        }
    }

    /** Drop entries whose companion has not requested a lease within the TTL window. */
    private static void evictStaleLocked(long now) {
        if (ROTATION.isEmpty()) return;
        for (Iterator<RotationQueue.Entry> it = ROTATION.iterator(); it.hasNext(); ) {
            RotationQueue.Entry e = it.next();
            if (now - e.lastSeenTick() > ENTRY_TTL_TICKS) {
                it.remove();
                IN_ROTATION.remove(e.id());
            }
        }
    }

    /** Load-shedding ladder: generous when healthy, zero new grants above 45 MSPT. */
    private static int capacityFor(float mspt) {
        if (!Float.isFinite(mspt) || mspt <= 0.0F) return 3; // unknown/absent metric: stay generous
        if (mspt > 45.0F) return 0;
        if (mspt > 35.0F) return 1;
        if (mspt > 25.0F) return 2;
        return 3;
    }

    /** One-line diagnostics for the inspect_ai tool. */
    public static String status() {
        synchronized (LOCK) {
            return "capacity=" + currentCapacity + " grantedThisTick=" + grantedThisTick
                    + " waiting=" + ROTATION.size() + " entryTtlTicks=" + ENTRY_TTL_TICKS + " " + lastShed;
        }
    }

    /**
     * Pure queue logic, extracted so the stuck-rotation behaviour can be unit-tested without
     * a Minecraft server (see {@code CompanionWorkBudgetTest}). An entry is a requester UUID
     * plus the game tick of its most recent lease request.
     */
    static final class RotationQueue {
        record Entry(UUID id, long lastSeenTick) { Entry withLastSeen(long t) { return new Entry(id, t); } }

        private final Deque<Entry> queue = new ArrayDeque<>();
        private final int maxSize;
        private final long entryTtlTicks;

        RotationQueue(int maxSize, long entryTtlTicks) {
            this.maxSize = Math.max(1, maxSize);
            this.entryTtlTicks = Math.max(1, entryTtlTicks);
        }

        /** Add the requester or refresh its heartbeat; keeps its FIFO queue position. */
        void touch(UUID id, long now) {
            if (id == null) return;
            for (Entry e : queue) {
                if (!e.id().equals(id)) continue;
                // Heartbeat in place: rebuild so the entry keeps its FIFO position.
                ArrayDeque<Entry> next = new ArrayDeque<>(queue.size());
                for (Entry x : queue) next.addLast(x.id().equals(id) ? new Entry(id, now) : x);
                queue.clear();
                queue.addAll(next);
                return;
            }
            if (queue.size() >= maxSize) return; // bounded: retry next tick
            queue.addLast(new Entry(id, now));
        }

        /** True when this requester is at the head (or the queue holds only it). */
        boolean mayRun(UUID id) {
            if (id == null || queue.isEmpty()) return queue.isEmpty();
            Entry head = queue.peekFirst();
            return head.id().equals(id) || queue.size() == 1;
        }

        /** Remove every entry that has not been touched within the TTL; returns the evicted count. */
        int evictStale(long now) {
            int evicted = 0;
            for (Iterator<Entry> it = queue.iterator(); it.hasNext(); ) {
                Entry e = it.next();
                if (now - e.lastSeenTick() > entryTtlTicks) { it.remove(); evicted++; }
            }
            return evicted;
        }

        void remove(UUID id) {
            if (id == null) return;
            for (Iterator<Entry> it = queue.iterator(); it.hasNext(); ) {
                if (it.next().id().equals(id)) { it.remove(); return; }
            }
        }

        UUID head() { return queue.isEmpty() ? null : queue.peekFirst().id(); }
        int size() { return queue.size(); }
        boolean contains(UUID id) {
            if (id == null) return false;
            for (Entry e : queue) if (e.id().equals(id)) return true;
            return false;
        }
        Set<UUID> ids() {
            Set<UUID> out = new HashSet<>();
            for (Entry e : queue) out.add(e.id());
            return out;
        }
    }
}
