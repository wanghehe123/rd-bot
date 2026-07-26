package com.wish.rd.exec.repair.runtime;

/** Raised when a persisted snapshot requests a runtime with no registered executor. */
public final class UnsupportedAgentRuntimeException extends IllegalStateException {

    public UnsupportedAgentRuntimeException(String message) {
        super(message);
    }
}
