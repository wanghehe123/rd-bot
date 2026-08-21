package com.wish.rd.engine.requirement.remediation;

import com.wish.rd.engine.requirement.remediation.impl.InMemoryAgentRemediationRoundStore;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationKind;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationRound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRemediationCoordinatorTest {

    private final InMemoryAgentRemediationRoundStore store = new InMemoryAgentRemediationRoundStore();
    private final AgentRemediationCoordinator coordinator = new AgentRemediationCoordinator(store);

    @Test
    void shouldReplayTheSameSourceAndKeepKindsIndependent() {
        AgentRemediationRound first = coordinator.claim(product("round-1", "qa-stage-1", 2, 2));
        AgentRemediationRound replay = coordinator.claim(product("different-id", "qa-stage-1", 2, 2));
        AgentRemediationRound protocol = coordinator.claim(protocol("protocol-1", "qa-stage-1", 2));

        assertSame(first, replay);
        assertEquals(1, first.remediationNo());
        assertEquals(1, protocol.remediationNo());
        assertEquals(AgentRemediationKind.QA_PROTOCOL_RETRY, protocol.kind());
    }

    @Test
    void shouldBoundProductFixesAtTwoAndProtocolRetryAtOne() {
        assertEquals(1, coordinator.claim(product("round-1", "qa-stage-1", 2, 2)).remediationNo());
        assertEquals(2, coordinator.claim(product("round-2", "qa-stage-2", 3, 3)).remediationNo());
        assertThrows(AgentRemediationLimitExceededException.class,
                () -> coordinator.claim(product("round-3", "qa-stage-3", 3, 3)));

        assertEquals(1, coordinator.claim(protocol("protocol-1", "qa-stage-4", 2)).remediationNo());
        assertThrows(AgentRemediationLimitExceededException.class,
                () -> coordinator.claim(protocol("protocol-2", "qa-stage-5", 3)));
    }

    @Test
    void shouldRejectAttemptsBeyondHardRoleLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.claim(product("round", "qa-stage", 4, 2)));
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.claim(product("round", "qa-stage", 2, 4)));
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.claim(protocol("protocol", "qa-stage", 4)));
    }

    private static AgentRemediationRoundStore.ClaimDraft product(
            String roundId,
            String sourceStageRunId,
            int codingAttempt,
            int qaAttempt
    ) {
        return new AgentRemediationRoundStore.ClaimDraft(
                roundId, "task-1", AgentRemediationKind.QA_PRODUCT_FIX,
                sourceStageRunId, "command-" + sourceStageRunId,
                "sha256:" + "1".repeat(64), 7L, 3L,
                "coding-" + roundId, codingAttempt,
                "qa-" + roundId, qaAttempt,
                "command-" + roundId,
                "{\"reason\":\"verified defect\"}", "sha256:" + "2".repeat(64),
                1000L
        );
    }

    private static AgentRemediationRoundStore.ClaimDraft protocol(
            String roundId,
            String sourceStageRunId,
            int qaAttempt
    ) {
        return new AgentRemediationRoundStore.ClaimDraft(
                roundId, "task-1", AgentRemediationKind.QA_PROTOCOL_RETRY,
                sourceStageRunId, "command-" + sourceStageRunId,
                "sha256:" + "3".repeat(64), 7L, 3L,
                "", 0,
                "qa-" + roundId, qaAttempt,
                "command-" + roundId,
                "{\"kind\":\"missing-result\"}", "sha256:" + "4".repeat(64),
                1000L
        );
    }
}
