package com.yaseen.npcmind.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerThreadGuardTest {
    @Test
    void acceptsOnlyLogicalServerThreadWithServer() {
        assertTrue(ServerThreadGuard.allows(false, true, true, true));
        assertFalse(ServerThreadGuard.allows(true, true, true, true), "client-side level is never accepted");
        assertFalse(ServerThreadGuard.allows(false, false, true, true), "non-server level is never accepted");
        assertFalse(ServerThreadGuard.allows(false, true, false, true), "missing server is rejected");
        assertFalse(ServerThreadGuard.allows(false, true, true, false), "wrong thread is rejected");
    }
}
