package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.impl.InMemoryAgentToolPolicyStore;
import com.wish.rd.rag.project.agent.model.AgentToolPolicy;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolPolicyServiceTest {

    @Test
    void defaultQaPolicyDeniesWriteTools() {
        AgentToolPolicy policy = AgentToolPolicy.defaultQaPolicy();
        assertEquals(2L, policy.version());
        assertEquals(Set.of("read", "bash", "rd_submit_result"), Set.copyOf(policy.effectiveAllow()));
        assertTrue(policy.deny().containsAll(Set.of("edit", "write")));
        assertFalse(policy.hostAllow().contains("edit"));
        assertFalse(policy.hostAllow().contains("write"));
    }

    @Test
    void rejectsProjectAllowThatExceedsHostUpperBound() {
        AgentToolPolicyService service = new AgentToolPolicyService(new InMemoryAgentToolPolicyStore());
        assertThrows(IllegalArgumentException.class, () -> service.register(new AgentToolPolicy(
                "coding", 1L, Set.of("read"), Set.of("read", "bash"), Set.of(), true
        )));
    }

    @Test
    void persistsImmutableVersionAndComputesEffectiveAllow() {
        AgentToolPolicyStore store = new InMemoryAgentToolPolicyStore();
        AgentToolPolicyService service = new AgentToolPolicyService(store);
        AgentToolPolicy policy = service.register(new AgentToolPolicy(
                "coding", 2L, Set.of("read", "bash"), Set.of("read", "bash"), Set.of("bash"), true
        ));

        assertEquals(policy, service.find("coding", 2L).orElseThrow());
        assertEquals(Set.of("read"), Set.copyOf(policy.effectiveAllow()));
        assertThrows(IllegalStateException.class, () -> store.save(new AgentToolPolicy(
                "coding", 2L, Set.of("read"), Set.of("read"), Set.of(), true
        )));
    }
}
