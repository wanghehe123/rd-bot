package com.wish.rd.rag.project.agent.impl;

import com.wish.rd.rag.project.agent.AgentToolPolicyStore;
import com.wish.rd.rag.project.agent.model.AgentToolPolicy;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Concurrent in-memory tool-policy store for local mode and tests. */
public final class InMemoryAgentToolPolicyStore implements AgentToolPolicyStore {

    private final Map<String, AgentToolPolicy> policies = new ConcurrentHashMap<>();

    @Override
    public AgentToolPolicy save(AgentToolPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        String key = key(policy.policyId(), policy.version());
        AgentToolPolicy previous = policies.putIfAbsent(key, policy);
        if (previous != null && !previous.equals(policy)) {
            throw new IllegalStateException("tool policy version is immutable: " + key);
        }
        return previous == null ? policy : previous;
    }

    @Override
    public Optional<AgentToolPolicy> find(String policyId, long version) {
        return Optional.ofNullable(policies.get(key(policyId, version)));
    }

    @Override
    public Optional<AgentToolPolicy> findLatest(String policyId) {
        String normalized = policyId == null ? "" : policyId.strip();
        return policies.values().stream()
                .filter(policy -> policy.policyId().equals(normalized))
                .max(Comparator.comparingLong(AgentToolPolicy::version));
    }

    private static String key(String policyId, long version) {
        return (policyId == null ? "" : policyId.strip()) + "\u0000" + version;
    }
}
