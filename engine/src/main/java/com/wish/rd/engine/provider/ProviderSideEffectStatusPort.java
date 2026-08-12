package com.wish.rd.engine.provider;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.provider.model.ProviderFallbackSideEffectSafety;

/**
 * Resolves provider-fallback safety from Host-owned attempt and external
 * side-effect state. Agent-declared safety is input evidence, never authority.
 */
@FunctionalInterface
public interface ProviderSideEffectStatusPort {

    ProviderFallbackSideEffectSafety resolve(Request request);

    static ProviderSideEffectStatusPort unavailable() {
        return request -> ProviderFallbackSideEffectSafety.unknown(
                "host-owned clean attempt evidence is missing"
        );
    }

    record Request(
            String taskId,
            String stageRunId,
            String idempotencyKey,
            int stageAttemptNo,
            AgentRole role,
            ProviderFallbackSideEffectSafety executorEvidence
    ) {
        public Request {
            taskId = safe(taskId);
            stageRunId = safe(stageRunId);
            idempotencyKey = safe(idempotencyKey);
            stageAttemptNo = Math.max(0, stageAttemptNo);
            executorEvidence = executorEvidence == null
                    ? ProviderFallbackSideEffectSafety.unknown(
                            "executor-owned provider-attempt evidence is missing"
                    )
                    : executorEvidence;
        }

        private static String safe(String value) {
            return value == null ? "" : value.strip();
        }
    }
}
