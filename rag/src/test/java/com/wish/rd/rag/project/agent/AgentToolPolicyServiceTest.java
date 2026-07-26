package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.impl.InMemoryAgentToolPolicyStore;
import com.wish.rd.rag.project.agent.model.AgentToolPolicy;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentToolPolicyServiceTest {

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
