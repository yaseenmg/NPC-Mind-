package com.yaseen.npcmind.agent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded worker-pool factory; rejection is explicit and never runs work on the submitter. */
final class BoundedWorkerPool {
    private BoundedWorkerPool() {}

    static ThreadPoolExecutor create(int workers, int queueCapacity, ThreadFactory threadFactory) {
        if (workers < 1) throw new IllegalArgumentException("workers must be positive");
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be positive");
        if (threadFactory == null) throw new NullPointerException("threadFactory");
        return new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), threadFactory, new ThreadPoolExecutor.AbortPolicy());
    }
}
