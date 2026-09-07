package com.wish.rd.engine.requirement.query;

/** Cursor or query parameters are invalid for the selected Coding identity. */
public final class CodingMeaBadRequestException extends RuntimeException {

    /**
     * Creates a bad-request failure.
     *
     * @param message operator-safe reason
     */
    public CodingMeaBadRequestException(String message) {
        super(message);
    }
}
