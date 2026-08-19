package com.wish.rd.engine.requirement.remediation;

import com.wish.rd.engine.requirement.remediation.model.AgentRemediationKind;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationRound;

import java.util.List;
import java.util.Optional;

public interface AgentRemediationRoundStore {

    ClaimResult claimNext(ClaimDraft draft, int maximumRounds);

    Optional<AgentRemediationRound> findBySource(String sourceStageRunId, AgentRemediationKind kind);

    List<AgentRemediationRound> listByTask(String taskId, AgentRemediationKind kind);

    record ClaimDraft(
            String roundId,
            String taskId,
            AgentRemediationKind kind,
            String sourceStageRunId,
            String sourceCommandId,
            String sourceResultHash,
            long sourceTaskVersion,
            long sourceFencingToken,
            String targetCodingStageRunId,
            int targetCodingAttemptNo,
            String targetQaStageRunId,
            int targetQaAttemptNo,
            String firstCommandId,
            String requestJson,
            String requestHash,
            long createdAtEpochMillis
    ) { }

    record ClaimResult(Outcome outcome, AgentRemediationRound round) {
        public enum Outcome { CLAIMED, REPLAY, EXHAUSTED }
    }
}
