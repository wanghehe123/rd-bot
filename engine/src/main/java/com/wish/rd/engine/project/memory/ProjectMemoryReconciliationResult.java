package com.wish.rd.engine.project.memory;

/** Outcome of one bounded reconciliation scan. */
public record ProjectMemoryReconciliationResult(int examined, int registered, int skippedExisting) {
}
