package com.wish.rd.engine.requirement.remediation;

import com.wish.rd.engine.requirement.remediation.model.AgentRemediationKind;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationRound;

import java.util.Objects;

public final class AgentRemediationCoordinator {
    public static final int MAX_ROLE_ATTEMPT_NO = 3;

    private final AgentRemediationRoundStore store;

    public AgentRemediationCoordinator(AgentRemediationRoundStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public AgentRemediationRound claim(AgentRemediationRoundStore.ClaimDraft draft) {
        Objects.requireNonNull(draft, "draft");
        validateAttempts(draft);
        AgentRemediationRoundStore.ClaimResult result = store.claimNext(
                draft, draft.kind().maximumRounds());
        if (result.outcome() == AgentRemediationRoundStore.ClaimResult.Outcome.EXHAUSTED) {
            throw new AgentRemediationLimitExceededException(
                    draft.kind() + " remediation limit has been reached for task " + draft.taskId());
        }
        return result.round();
    }

    private static void validateAttempts(AgentRemediationRoundStore.ClaimDraft draft) {
        if (draft.kind() == null) throw new IllegalArgumentException("kind is required");
        if (draft.targetQaAttemptNo() < 1 || draft.targetQaAttemptNo() > MAX_ROLE_ATTEMPT_NO) {
            throw new IllegalArgumentException("target QA attempt must be between 1 and 3");
        }
        if (draft.kind() == AgentRemediationKind.QA_PRODUCT_FIX) {
            if (draft.targetCodingAttemptNo() < 1 || draft.targetCodingAttemptNo() > MAX_ROLE_ATTEMPT_NO) {
                throw new IllegalArgumentException("target Coding attempt must be between 1 and 3");
            }
        } else if (draft.targetCodingAttemptNo() != 0
                || (draft.targetCodingStageRunId() != null && !draft.targetCodingStageRunId().isBlank())) {
            throw new IllegalArgumentException("protocol retry must not target Coding");
        }
    }
}
