package com.wish.rd.engine.requirement.query;

/** Task-owned identity is missing or belongs to another task. */
public final class CodingMeaNotFoundException extends RuntimeException {

    /**
     * Creates a not-found failure.
     *
     * @param message operator-safe reason
     */
    public CodingMeaNotFoundException(String message) {
        super(message);
    }
}
