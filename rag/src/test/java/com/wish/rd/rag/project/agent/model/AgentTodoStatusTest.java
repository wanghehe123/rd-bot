package com.wish.rd.rag.project.agent.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTodoStatusTest {

    @Test
    void allowsDocumentedTransitions() {
        assertTrue(AgentTodoStatus.isValidTransition(AgentTodoStatus.PENDING, AgentTodoStatus.IN_PROGRESS));
        assertTrue(AgentTodoStatus.isValidTransition(AgentTodoStatus.IN_PROGRESS, AgentTodoStatus.DONE));
        assertTrue(AgentTodoStatus.isValidTransition(AgentTodoStatus.BLOCKED, AgentTodoStatus.CANCELLED));
    }

    @Test
    void rejectsTerminalAndInvalidTransitions() {
        assertFalse(AgentTodoStatus.isValidTransition(AgentTodoStatus.DONE, AgentTodoStatus.IN_PROGRESS));
        assertFalse(AgentTodoStatus.isValidTransition(AgentTodoStatus.CANCELLED, AgentTodoStatus.PENDING));
        assertFalse(AgentTodoStatus.isValidTransition(AgentTodoStatus.PENDING, AgentTodoStatus.DONE));
    }
}
