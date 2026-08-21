package com.wish.rd.engine.requirement.remediation.impl;

import com.wish.rd.engine.requirement.remediation.AgentRemediationRoundStore;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationKind;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationRound;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryAgentRemediationRoundStore implements AgentRemediationRoundStore {
    private final Map<String, AgentRemediationRound> rounds = new LinkedHashMap<>();

    @Override
    public synchronized ClaimResult claimNext(ClaimDraft draft, int maximumRounds) {
        AgentRemediationRound existing = findBySource(draft.sourceStageRunId(), draft.kind()).orElse(null);
        if (existing != null) {
            requireSameReplay(existing, draft);
            return new ClaimResult(ClaimResult.Outcome.REPLAY, existing);
        }
        int next = rounds.values().stream()
                .filter(round -> round.taskId().equals(draft.taskId()) && round.kind() == draft.kind())
                .mapToInt(AgentRemediationRound::remediationNo)
                .max().orElse(0) + 1;
        if (next > maximumRounds) {
            return new ClaimResult(ClaimResult.Outcome.EXHAUSTED, null);
        }
        AgentRemediationRound round = new AgentRemediationRound(
                draft.roundId(), draft.taskId(), draft.kind(), next,
                draft.sourceStageRunId(), draft.sourceCommandId(), draft.sourceResultHash(),
                draft.sourceTaskVersion(), draft.sourceFencingToken(),
                draft.targetCodingStageRunId(), draft.targetCodingAttemptNo(),
                draft.targetQaStageRunId(), draft.targetQaAttemptNo(), draft.firstCommandId(),
                draft.requestJson(), draft.requestHash(), AgentRemediationRound.Status.CLAIMED,
                0L, draft.createdAtEpochMillis());
        if (rounds.putIfAbsent(round.roundId(), round) != null) {
            throw new IllegalStateException("remediation roundId already exists");
        }
        return new ClaimResult(ClaimResult.Outcome.CLAIMED, round);
    }

    @Override
    public synchronized Optional<AgentRemediationRound> findBySource(
            String sourceStageRunId,
            AgentRemediationKind kind
    ) {
        return rounds.values().stream()
                .filter(round -> round.sourceStageRunId().equals(sourceStageRunId) && round.kind() == kind)
                .findFirst();
    }

    @Override
    public synchronized List<AgentRemediationRound> listByTask(String taskId, AgentRemediationKind kind) {
        return rounds.values().stream()
                .filter(round -> round.taskId().equals(taskId) && round.kind() == kind)
                .sorted(Comparator.comparingInt(AgentRemediationRound::remediationNo))
                .toList();
    }

    private static void requireSameReplay(AgentRemediationRound existing, ClaimDraft draft) {
        List<Boolean> matches = new ArrayList<>();
        matches.add(existing.taskId().equals(draft.taskId()));
        matches.add(existing.sourceCommandId().equals(draft.sourceCommandId()));
        matches.add(existing.sourceResultHash().equals(draft.sourceResultHash()));
        matches.add(existing.sourceTaskVersion() == draft.sourceTaskVersion());
        matches.add(existing.sourceFencingToken() == draft.sourceFencingToken());
        matches.add(existing.requestHash().equals(draft.requestHash()));
        if (matches.stream().anyMatch(match -> !match)) {
            throw new IllegalStateException("conflicting remediation replay for source stage");
        }
    }
}
