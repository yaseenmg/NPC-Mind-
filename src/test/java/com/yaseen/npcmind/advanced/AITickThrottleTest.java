package com.yaseen.npcmind.advanced;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-Java unit tests for the decision cadence limiter; no Minecraft world is needed. */
class AITickThrottleTest {
    @Test
    void keyRunsImmediatelyThenWaitsForItsInterval() {
        AITickThrottle throttle = new AITickThrottle();

        assertTrue(throttle.due(10, "observation", 5));
        assertFalse(throttle.due(11, "observation", 5));
        assertFalse(throttle.due(14, "observation", 5));
        assertTrue(throttle.due(15, "observation", 5));
    }

    @Test
    void keysHaveIndependentSchedules() {
        AITickThrottle throttle = new AITickThrottle();

        assertTrue(throttle.due(100, "autonomy", 5));
        assertTrue(throttle.due(100, "observation", 10));
        assertFalse(throttle.due(101, "autonomy", 5));
        assertFalse(throttle.due(101, "observation", 10));
        assertTrue(throttle.due(105, "autonomy", 5));
        assertFalse(throttle.due(105, "observation", 10));
    }

    @Test
    void intervalsAreClampedAndResetWorks() {
        AITickThrottle throttle = new AITickThrottle();

        assertTrue(throttle.due(1, "min", 0));
        assertTrue(throttle.due(1, "max", Integer.MAX_VALUE));
        assertTrue(throttle.due(2, "min", 1));
        assertFalse(throttle.due(1200, "max", Integer.MAX_VALUE));
        assertTrue(throttle.due(1201, "max", Integer.MAX_VALUE));

        throttle.reset("max");
        assertTrue(throttle.due(1201, "max", 100));
        throttle.clear();
        assertTrue(throttle.due(1201, "max", 100));
    }

    @Test
    void blankKeysAreNotThrottled() {
        AITickThrottle throttle = new AITickThrottle();
        assertTrue(throttle.due(1, "", 100));
        assertTrue(throttle.due(1, null, 100));
    }
}
