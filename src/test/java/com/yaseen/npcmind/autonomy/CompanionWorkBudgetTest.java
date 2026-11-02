package com.yaseen.npcmind.autonomy;

import com.yaseen.npcmind.autonomy.CompanionWorkBudget.RotationQueue;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-Java unit tests for the work-budget rotation queue; no Minecraft world is needed.
 *
 * <p>Regression coverage for the stuck-queue review finding: a companion that stops asking
 * for a lease (autonomy mode changed, long task started, exploration disabled, entity removed
 * without cleanup) must never pin the head of the rotation and starve the waiters behind it.</p>
 */
class CompanionWorkBudgetTest {
    private static final long TTL = CompanionWorkBudget.ENTRY_TTL_TICKS;

    /** The exact review scenario: A adds itself, B waits, A stops requesting, B must get the lease. */
    @Test
    void secondCompanionGetsLeaseAfterFirstStopsRequesting() {
        RotationQueue q = new RotationQueue(32, TTL);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        q.touch(a, 0);           // A requests (granted, then keeps working without asking again)
        q.touch(b, 1);           // B requests and has to wait behind A
        assertTrue(q.mayRun(a));
        assertFalse(q.mayRun(b), "B must wait while A is a fresh head");
        assertEquals(2, q.size());

        // A never asks again. The entry must self-expire, not sit at the head forever.
        assertEquals(1, q.evictStale(TTL + 1), "A's stale entry should be evicted after the TTL while B remains fresh");
        assertFalse(q.contains(a));
        assertTrue(q.mayRun(b), "B must become the head and get the lease once A stops requesting");
        assertEquals(b, q.head());
    }

    /** A fresh head must not be evicted: TTL is measured per entry from its last request. */
    @Test
    void evictStaleOnlyRemovesExpiredEntries() {
        RotationQueue q = new RotationQueue(32, TTL);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        q.touch(a, 0);
        q.touch(b, 50);
        assertEquals(0, q.evictStale(55), "both entries are still fresh");
        assertEquals(1, q.evictStale(TTL + 1), "only A (age 61) expires; B (age 11) stays");
        assertFalse(q.contains(a));
        assertTrue(q.contains(b));
        assertTrue(q.mayRun(b));
    }

    /** Every request is a heartbeat: an active waiter keeps its FIFO position. */
    @Test
    void heartbeatKeepsActiveWaitersInFifoOrder() {
        RotationQueue q = new RotationQueue(32, TTL);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();

        q.touch(a, 0);
        q.touch(b, 1);
        q.touch(c, 2);
        q.touch(b, 40);          // B heartbeats: stays second, does not jump the queue
        q.touch(c, 41);          // C remains active behind B
        assertEquals(0, q.evictStale(55), "heartbeats keep all three entries fresh");
        assertEquals(3, q.size());
        assertFalse(q.mayRun(b));
        assertFalse(q.mayRun(c));

        assertEquals(1, q.evictStale(TTL + 3)); // a (age 63) expires, b/c keep their order
        assertEquals(b, q.head());
        q.remove(b);
        assertTrue(q.mayRun(c));
    }

    /** Normal rotation: release removes the head so the next waiter runs. */
    @Test
    void releaseAdvancesRotation() {
        RotationQueue q = new RotationQueue(32, TTL);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        q.touch(a, 0);
        assertTrue(q.mayRun(a));
        q.remove(a);             // A finished its decision and released
        q.touch(b, 1);
        assertTrue(q.mayRun(b), "B runs immediately once the queue effectively only holds it");
    }

    /** An empty queue lets the only requester run at once. */
    @Test
    void emptyQueueRunsImmediately() {
        RotationQueue q = new RotationQueue(32, TTL);
        UUID a = UUID.randomUUID();
        assertTrue(q.mayRun(a), "no competition: the requester is granted without queueing");
        q.touch(a, 0);
        assertTrue(q.mayRun(a));
    }

    /** The queue stays bounded even if many companions request. */
    @Test
    void queueIsBounded() {
        RotationQueue q = new RotationQueue(4, TTL);
        for (int i = 0; i < 10; i++) q.touch(UUID.randomUUID(), i);
        assertEquals(4, q.size(), "queue must not grow past maxSize");
    }
}
