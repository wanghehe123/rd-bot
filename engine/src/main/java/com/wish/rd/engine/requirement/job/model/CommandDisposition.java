package com.wish.rd.engine.requirement.job.model;

/** Describes the durable command outcome the Host must persist for a stage plan. */
public enum CommandDisposition {
    /** The current command completed and its optional continuation may be admitted. */
    SUCCEEDED,
    /** The workflow reached a non-retryable terminal failure. */
    TERMINAL_FAILURE,
    /** Technical execution failed before a terminal business decision and may be retried. */
    RETRYABLE_TECHNICAL_FAILURE
}
