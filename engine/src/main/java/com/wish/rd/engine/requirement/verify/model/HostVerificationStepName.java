package com.wish.rd.engine.requirement.verify.model;

/**
 * Named steps inside one host verification run.
 *
 * <p>Host verification is not a fifth {@code AgentRole}; BUILD then STATIC are the
 * only steps. Used later by step records, store rows, and the executor adapter.
 */
public enum HostVerificationStepName {
    BUILD,
    STATIC
}
