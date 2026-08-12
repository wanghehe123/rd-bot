package com.wish.rd.exec.repair.provider;

/**
 * Host-owned preflight executed before a repair executor invokes an alternate
 * model provider. Implementations may consult durable publication/tool ledgers.
 */
@FunctionalInterface
public interface ProviderFallbackPreflightPort {

    Decision evaluate(Request request);

    /**
     * Compatibility policy for non-Spring tests. Production wiring supplies a
     * durable adapter; known side-effect roles fail closed when it is absent.
     */
    static ProviderFallbackPreflightPort unavailable() {
        return request -> request != null && request.sideEffectRole()
                ? Decision.block("host side-effect ledger preflight is unavailable")
                : Decision.allow("generation-only fallback does not require a side-effect ledger");
    }

    record Request(
            String workflowTaskId,
            String stageRunId,
            String role,
            String failedProvider,
            String failedStatus,
            String fallbackProvider,
            String attemptId,
            boolean freshWorkspace,
            boolean highRiskWork
    ) {
        public Request {
            workflowTaskId = safe(workflowTaskId);
            stageRunId = safe(stageRunId);
            role = safe(role).toUpperCase(java.util.Locale.ROOT);
            failedProvider = safe(failedProvider);
            failedStatus = safe(failedStatus);
            fallbackProvider = safe(fallbackProvider);
            attemptId = safe(attemptId);
        }

        /** Backward-compatible constructor for callers without Host risk classification. */
        public Request(
                String workflowTaskId,
                String stageRunId,
                String role,
                String failedProvider,
                String failedStatus,
                String fallbackProvider,
                String attemptId,
                boolean freshWorkspace
        ) {
            this(
                    workflowTaskId,
                    stageRunId,
                    role,
                    failedProvider,
                    failedStatus,
                    fallbackProvider,
                    attemptId,
                    freshWorkspace,
                    false
            );
        }

        public boolean sideEffectRole() {
            return "CODING_AGENT".equals(role)
                    || "BUG_CODING_AGENT".equals(role)
                    || "QA_AGENT".equals(role);
        }

        private static String safe(String value) {
            return value == null ? "" : value.strip();
        }
    }

    record Decision(boolean allowed, String reason) {
        public Decision {
            reason = reason == null ? "" : reason.strip();
        }

        public static Decision allow(String reason) {
            return new Decision(true, reason);
        }

        public static Decision block(String reason) {
            return new Decision(false, reason);
        }
    }
}
