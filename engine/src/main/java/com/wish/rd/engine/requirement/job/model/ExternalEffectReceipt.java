package com.wish.rd.engine.requirement.job.model;

/**
 * Durable evidence for an external side effect performed while computing a stage plan.
 *
 * @param kind effect family
 * @param operationId stable external-operation identity
 * @param durableState state durably recorded by the effect ledger
 * @param receiptJson normalized receipt payload
 */
public record ExternalEffectReceipt(Kind kind, String operationId, String durableState, String receiptJson) {

    /** External side-effect families recognized by the stage finalizer. */
    public enum Kind {
        /** No external effect accompanies the plan. */
        NONE,
        /** A publication ledger receipt accompanies the plan. */
        PUBLICATION,
        /** A provider-tool operation receipt accompanies the plan. */
        PROVIDER_TOOL
    }

    /** Normalizes receipt fields and validates presence by effect kind. */
    public ExternalEffectReceipt {
        if (kind == null) {
            throw new IllegalArgumentException("effect receipt kind must be explicit");
        }
        operationId = safe(operationId);
        durableState = safe(durableState);
        receiptJson = json(receiptJson);
        if (kind == Kind.NONE && (!operationId.isBlank() || !durableState.isBlank())) {
            throw new IllegalArgumentException("NONE receipt must not carry operation identity or durable state");
        }
        if (kind != Kind.NONE && (operationId.isBlank() || durableState.isBlank())) {
            throw new IllegalArgumentException("external effect receipt requires operationId and durableState");
        }
    }

    /**
     * Returns the no-effect receipt.
     *
     * @return normalized empty receipt
     */
    public static ExternalEffectReceipt none() {
        return new ExternalEffectReceipt(Kind.NONE, "", "", "{}");
    }

    /**
     * Returns whether the Host may atomically finalize the plan with this receipt.
     *
     * @return false for ambiguous or still-prepared external evidence
     */
    public boolean isFinalizable() {
        String state = durableState.toUpperCase(java.util.Locale.ROOT);
        return switch (kind) {
            case NONE -> true;
            case PUBLICATION -> state.equals("PR_CONFIRMED") || state.equals("COMMITTED");
            // ProviderToolOperation has one authoritative retained-effect terminal: COMMITTED.
            case PROVIDER_TOOL -> state.equals("COMMITTED");
        };
    }

    /**
     * Returns whether a stage outcome may be durably recorded with this receipt.
     *
     * <p>Finalizable receipts always pass. Non-finalizable {@link Kind#PUBLICATION} receipts are
     * allowed only for terminal failure dispositions so WAIT_RECONCILE plans can be recorded
     * without weakening {@link #isFinalizable()}.
     *
     * @param disposition planned command disposition
     * @return true when the receipt may be persisted with the plan
     */
    public boolean allowsOutcomeRecording(CommandDisposition disposition) {
        if (isFinalizable()) {
            return true;
        }
        return (disposition == CommandDisposition.TERMINAL_FAILURE
                || disposition == CommandDisposition.RETRYABLE_TECHNICAL_FAILURE)
                && kind == Kind.PUBLICATION
                && !operationId.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static String json(String value) {
        String normalized = safe(value);
        return normalized.isBlank() ? "{}" : normalized;
    }
}
