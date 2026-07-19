package com.wish.rd.engine.agent;

import com.wish.rd.engine.agent.model.AgentStageStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentStageTransitionsTest {

    @Test
    void retryableFailureShouldBeTerminalAndMustNotMutateIntoRecovering() {
        assertTrue(AgentStageStatus.FAILED_RETRYABLE.isTerminal());
        assertThrows(IllegalStateException.class, () -> AgentStageTransitions.ensureTransition(
                AgentStageStatus.FAILED_RETRYABLE,
                AgentStageStatus.RECOVERING));
    }

    @Test
    void everyNonterminalStageShouldAllowCancellation() {
        for (AgentStageStatus status : AgentStageStatus.values()) {
            if (!status.isTerminal()) {
                AgentStageTransitions.ensureTransition(status, AgentStageStatus.CANCELLED);
            }
        }
    }
}
