package com.wish.rd.engine.provider;

import java.util.Locale;

/**
 * Host-owned safety evidence required before switching providers for side-effect work.
 *
 * <p>This value does not query or emulate a publication ledger. It records the
 * explicit operation/attempt invariant supplied by the caller so the fallback
 * policy can fail closed when that invariant is absent or ambiguous.
 *
 * @param state       evidence state for the provider switch
 * @param operationId auditable operation identity
 * @param attemptId   new attempt marker when one was created
 * @param outputReset whether the prior provider output directory was reset
 * @param reason      operator-facing audit reason
 */
public record ProviderFallbackSideEffectSafety(
        State state,
        String operationId,
        String attemptId,
        boolean outputReset,
        String reason
) {

    /**
     * Explicit states understood by the host policy.
     */
    public enum State {
        /** A distinct provider-switch attempt was created. */
        EXPLICIT_CLEAN_ATTEMPT,
        /** The provider-switch output directory was reset before retry. */
        EXPLICIT_OUTPUT_RESET,
        /** The operation state could not be established. */
        UNKNOWN,
        /** Conflicting operation evidence was observed. */
        AMBIGUOUS
    }

    public ProviderFallbackSideEffectSafety {
        state = state == null ? State.UNKNOWN : state;
        operationId = safe(operationId);
        attemptId = safe(attemptId);
        reason = safe(reason);
        if (reason.isBlank()) {
            reason = defaultReason(state);
        }
    }

    /**
     * Creates host evidence for a distinct, clean provider-switch attempt.
     *
     * @param operationId auditable operation identity
     * @param attemptId   new attempt marker
     * @return explicit clean-attempt evidence
     */
    public static ProviderFallbackSideEffectSafety explicitCleanAttempt(
            String operationId,
            String attemptId
    ) {
        return new ProviderFallbackSideEffectSafety(
                State.EXPLICIT_CLEAN_ATTEMPT,
                operationId,
                attemptId,
                false,
                "provider switch uses a distinct clean attempt marker"
        );
    }

    /**
     * Creates host evidence for a provider switch whose output directory was reset.
     *
     * @param operationId auditable operation identity
     * @return explicit output-reset evidence
     */
    public static ProviderFallbackSideEffectSafety explicitOutputReset(String operationId) {
        return new ProviderFallbackSideEffectSafety(
                State.EXPLICIT_OUTPUT_RESET,
                operationId,
                "",
                true,
                "provider switch reset the prior provider output directory"
        );
    }

    /**
     * Creates fail-closed evidence for an unknown operation state.
     *
     * @param reason diagnostic reason
     * @return unknown safety evidence
     */
    public static ProviderFallbackSideEffectSafety unknown(String reason) {
        return new ProviderFallbackSideEffectSafety(
                State.UNKNOWN,
                "",
                "",
                false,
                reason
        );
    }

    /**
     * Creates fail-closed evidence for conflicting operation state.
     *
     * @param reason diagnostic reason
     * @return ambiguous safety evidence
     */
    public static ProviderFallbackSideEffectSafety ambiguous(String reason) {
        return new ProviderFallbackSideEffectSafety(
                State.AMBIGUOUS,
                "",
                "",
                false,
                reason
        );
    }

    /**
     * Returns whether this evidence satisfies the host-side side-effect gate.
     *
     * @return true only for an auditable clean attempt or output reset
     */
    public boolean isExplicitlySafe() {
        return switch (state) {
            case EXPLICIT_CLEAN_ATTEMPT -> !operationId.isBlank() && !attemptId.isBlank();
            case EXPLICIT_OUTPUT_RESET -> !operationId.isBlank() && outputReset;
            case UNKNOWN, AMBIGUOUS -> false;
        };
    }

    /**
     * Returns the diagnostic reason retained for policy and alert evidence.
     *
     * @return non-blank audit reason
     */
    public String auditReason() {
        return reason;
    }

    /**
     * Parses a serialized safety state without allowing unknown values to pass.
     *
     * @param raw serialized state
     * @return known state or {@link State#UNKNOWN}
     */
    public static State parseState(String raw) {
        String normalized = safe(raw).toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "EXPLICIT_CLEAN_ATTEMPT", "CLEAN_ATTEMPT", "NEW_ATTEMPT" ->
                    State.EXPLICIT_CLEAN_ATTEMPT;
            case "EXPLICIT_OUTPUT_RESET", "OUTPUT_RESET", "CLEAN_OUTPUT" ->
                    State.EXPLICIT_OUTPUT_RESET;
            case "AMBIGUOUS", "CONFLICTING" -> State.AMBIGUOUS;
            case "UNKNOWN", "UNKNOWN_OPERATION", "UNKNOWN_PUBLICATION", "" -> State.UNKNOWN;
            default -> State.UNKNOWN;
        };
    }

    private static String defaultReason(State state) {
        return switch (state) {
            case EXPLICIT_CLEAN_ATTEMPT ->
                    "explicit clean attempt marker is missing or invalid";
            case EXPLICIT_OUTPUT_RESET ->
                    "explicit output reset evidence is missing or invalid";
            case UNKNOWN ->
                    "side-effect operation state is unknown; explicit clean attempt or output reset is required";
            case AMBIGUOUS ->
                    "side-effect operation state is ambiguous; provider switch requires policy review";
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
