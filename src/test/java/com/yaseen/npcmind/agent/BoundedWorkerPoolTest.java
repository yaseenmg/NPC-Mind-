package com.yaseen.npcmind.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedWorkerPoolTest {
    @Test
    void rejectsWhenWorkersAndQueueAreFullWithoutRunningOnSubmitter() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean rejectedTaskRan = new AtomicBoolean(false);
        ThreadPoolExecutor executor = BoundedWorkerPool.create(1, 1, task -> {
            Thread thread = new Thread(task, "test-bounded-worker");
            thread.setDaemon(true);
            return thread;
        });
        try {
            executor.execute(() -> {
                started.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            executor.execute(() -> { }); // fills the only queue slot
            assertThrows(RejectedExecutionException.class,
                    () -> executor.execute(() -> rejectedTaskRan.set(true)));
            assertFalse(rejectedTaskRan.get(), "rejected work must not run on the caller/server thread");
            assertTrue(executor.getMaximumPoolSize() == 1);
            assertTrue(executor.getQueue().remainingCapacity() == 0);
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
