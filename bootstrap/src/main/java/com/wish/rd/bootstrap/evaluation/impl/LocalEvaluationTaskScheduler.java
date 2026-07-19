package com.wish.rd.bootstrap.evaluation.impl;

import com.wish.rd.engine.evaluation.EvaluationTaskSchedulerPort;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Owns the bounded evaluation worker pool without exposing a shared ExecutorService bean. */
public final class LocalEvaluationTaskScheduler implements EvaluationTaskSchedulerPort, AutoCloseable {
    private final ExecutorService executorService;

    public LocalEvaluationTaskScheduler(int parallelism) {
        executorService = Executors.newFixedThreadPool(
                Math.max(1, Math.min(8, parallelism)),
                Thread.ofPlatform().name("rd-evaluation-", 1).factory());
    }

    /** Schedules one local evaluation attempt. */
    @Override
    public void submit(Runnable task) {
        executorService.execute(task);
    }

    /** Stops accepting new work and bounds application shutdown wait time. */
    @Override
    public void close() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executorService.shutdownNow();
        }
    }
}
