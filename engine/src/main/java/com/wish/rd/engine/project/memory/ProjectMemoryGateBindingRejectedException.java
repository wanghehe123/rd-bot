package com.wish.rd.engine.project.memory;

/** Raised when a PRIMARY promotion is rejected by the frozen shadow gate. */
public final class ProjectMemoryGateBindingRejectedException extends RuntimeException {
    public ProjectMemoryGateBindingRejectedException(String message) {
        super(message);
    }
}
