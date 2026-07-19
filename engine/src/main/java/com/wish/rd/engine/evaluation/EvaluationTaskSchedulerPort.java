package com.wish.rd.engine.evaluation;

/** Schedules local evaluation work outside the HTTP request thread. */
@FunctionalInterface
public interface EvaluationTaskSchedulerPort {
    void submit(Runnable task);
}
