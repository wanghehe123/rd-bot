package com.wish.rd.rag.runtime;

import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.RdTaskType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies that each RD task type owns an explicit state-transition graph. */
class RdTaskTransitionPolicyTest {

    @Test
    void shouldAllowRequirementDeliveryAndRecoveryEdges() {
        assertDoesNotThrow(() -> allow(RdTaskType.REQUIREMENT, RdTaskStatus.CREATED, RdTaskStatus.MATERIAL_COLLECTING));
        assertDoesNotThrow(() -> allow(RdTaskType.REQUIREMENT, RdTaskStatus.PLAN_GENERATED, RdTaskStatus.WAITING_POLICY));
        assertDoesNotThrow(() -> allow(RdTaskType.REQUIREMENT, RdTaskStatus.WAITING_POLICY, RdTaskStatus.WAITING_APPROVAL));
        assertDoesNotThrow(() -> allow(RdTaskType.REQUIREMENT, RdTaskStatus.REPORTING, RdTaskStatus.COMPLETED));
        assertDoesNotThrow(() -> allow(RdTaskType.REQUIREMENT, RdTaskStatus.REPORTING, RdTaskStatus.FAILED_NEEDS_HUMAN));
        assertDoesNotThrow(() -> allow(RdTaskType.REQUIREMENT, RdTaskStatus.FAILED_NEEDS_HUMAN, RdTaskStatus.RECOVERING));
        assertDoesNotThrow(() -> allow(RdTaskType.REQUIREMENT, RdTaskStatus.CANCELLED, RdTaskStatus.RECOVERING));
        assertDoesNotThrow(() -> allow(RdTaskType.REQUIREMENT, RdTaskStatus.RECOVERING, RdTaskStatus.EXECUTING));
        assertDoesNotThrow(() -> allow(RdTaskType.REQUIREMENT, RdTaskStatus.RECOVERING, RdTaskStatus.VALIDATING));
        assertDoesNotThrow(() -> allow(RdTaskType.REQUIREMENT, RdTaskStatus.RECOVERING, RdTaskStatus.PR_CREATING));
        assertDoesNotThrow(() -> allow(
                RdTaskType.REQUIREMENT, RdTaskStatus.PR_CREATING, RdTaskStatus.FAILED_NEEDS_HUMAN));
        assertDoesNotThrow(() -> allow(
                RdTaskType.REQUIREMENT, RdTaskStatus.RECOVERING, RdTaskStatus.FAILED_RETRYABLE));
        assertDoesNotThrow(() -> allow(RdTaskType.REQUIREMENT, RdTaskStatus.EXECUTING, RdTaskStatus.CANCELLED));
        assertDoesNotThrow(() -> allow(RdTaskType.REQUIREMENT, RdTaskStatus.EXECUTING, RdTaskStatus.WAITING_USER_INPUT));
        assertDoesNotThrow(() -> allow(RdTaskType.REQUIREMENT, RdTaskStatus.WAITING_USER_INPUT, RdTaskStatus.EXECUTING));
        assertDoesNotThrow(() -> allow(RdTaskType.REQUIREMENT, RdTaskStatus.WAITING_USER_INPUT, RdTaskStatus.REJECTED));
        assertDoesNotThrow(() -> allow(RdTaskType.REQUIREMENT, RdTaskStatus.RECOVERING, RdTaskStatus.WAITING_USER_INPUT));
        assertDoesNotThrow(() -> allow(RdTaskType.REQUIREMENT, RdTaskStatus.CREATED, RdTaskStatus.DEAD_LETTERED));
        assertDoesNotThrow(() -> allow(
                RdTaskType.REQUIREMENT, RdTaskStatus.DEAD_LETTERED, RdTaskStatus.RECOVERING));
    }

    @Test
    void shouldRejectBugOnlySearchingStateForRequirement() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> allow(RdTaskType.REQUIREMENT, RdTaskStatus.CREATED, RdTaskStatus.SEARCHING)
        );

        assertEquals(
                "illegal REQUIREMENT task status transition: CREATED -> SEARCHING",
                exception.getMessage()
        );
    }

    @Test
    void shouldAllowBugFixFlowAndRejectRequirementOnlyMaterialState() {
        assertDoesNotThrow(() -> allow(RdTaskType.BUG_FIX, RdTaskStatus.CREATED, RdTaskStatus.SEARCHING));
        assertDoesNotThrow(() -> allow(RdTaskType.BUG_FIX, RdTaskStatus.SEARCHING, RdTaskStatus.EXECUTING));
        assertDoesNotThrow(() -> allow(RdTaskType.BUG_FIX, RdTaskStatus.EXECUTING, RdTaskStatus.COMMITTED));
        assertDoesNotThrow(() -> allow(RdTaskType.BUG_FIX, RdTaskStatus.EXECUTING, RdTaskStatus.CANCELLED));
        assertDoesNotThrow(() -> allow(RdTaskType.BUG_FIX, RdTaskStatus.FAILED_RETRYABLE, RdTaskStatus.SEARCHING));

        assertThrows(
                IllegalStateException.class,
                () -> allow(RdTaskType.BUG_FIX, RdTaskStatus.CREATED, RdTaskStatus.MATERIAL_COLLECTING)
        );
        assertThrows(
                IllegalStateException.class,
                () -> allow(RdTaskType.BUG_FIX, RdTaskStatus.EXECUTING, RdTaskStatus.WAITING_USER_INPUT)
        );
    }

    @Test
    void shouldRejectAllQnaStateTransitions() {
        assertThrows(
                IllegalStateException.class,
                () -> allow(RdTaskType.QNA, RdTaskStatus.CREATED, RdTaskStatus.EXECUTING)
        );
    }

    @Test
    void shouldAllowDeadLetterFromTerminalFailureAndCancelledStates() {
        assertDoesNotThrow(() -> allow(RdTaskType.REQUIREMENT, RdTaskStatus.REJECTED, RdTaskStatus.DEAD_LETTERED));
        assertDoesNotThrow(() -> allow(
                RdTaskType.REQUIREMENT, RdTaskStatus.FAILED_RETRYABLE, RdTaskStatus.DEAD_LETTERED));
        assertDoesNotThrow(() -> allow(
                RdTaskType.REQUIREMENT, RdTaskStatus.FAILED_NEEDS_HUMAN, RdTaskStatus.DEAD_LETTERED));
        assertDoesNotThrow(() -> allow(RdTaskType.REQUIREMENT, RdTaskStatus.CANCELLED, RdTaskStatus.DEAD_LETTERED));
        assertDoesNotThrow(() -> allow(
                RdTaskType.REQUIREMENT, RdTaskStatus.RECOVERING, RdTaskStatus.WAITING_APPROVAL));
        assertDoesNotThrow(() -> allow(RdTaskType.BUG_FIX, RdTaskStatus.REJECTED, RdTaskStatus.DEAD_LETTERED));
        assertDoesNotThrow(() -> allow(
                RdTaskType.BUG_FIX, RdTaskStatus.FAILED_NEEDS_HUMAN, RdTaskStatus.DEAD_LETTERED));
        assertDoesNotThrow(() -> allow(RdTaskType.BUG_FIX, RdTaskStatus.CANCELLED, RdTaskStatus.DEAD_LETTERED));
    }

    private void allow(RdTaskType taskType, RdTaskStatus source, RdTaskStatus target) {
        RdTaskTransitionPolicy.ensureTransition(taskType, source, target);
    }
}
