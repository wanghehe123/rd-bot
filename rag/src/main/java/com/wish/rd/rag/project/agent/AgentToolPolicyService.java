package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.model.AgentToolPolicy;

import java.util.Objects;
import java.util.Optional;

/** Validates and resolves immutable tool policies without handling credentials. */
public final class AgentToolPolicyService {

    private final AgentToolPolicyStore store;

    public AgentToolPolicyService(AgentToolPolicyStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    public AgentToolPolicy register(AgentToolPolicy policy) {
        validate(policy);
        return store.save(policy);
    }

    public Optional<AgentToolPolicy> find(String policyId, long version) {
        String normalized = policyId == null ? "" : policyId.strip();
        if (normalized.isBlank() || version <= 0L) {
            return Optional.empty();
        }
        return store.find(normalized, version);
    }

    public Optional<AgentToolPolicy> findLatest(String policyId) {
        String normalized = policyId == null ? "" : policyId.strip();
        return normalized.isBlank() ? Optional.empty() : store.findLatest(normalized);
    }

    public static void validate(AgentToolPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("tool policy must not be null");
        }
        if (policy.hostAllow().isEmpty()) {
            throw new IllegalArgumentException("hostAllow must not be empty");
        }
        if (!policy.allow().stream().allMatch(policy.hostAllow()::contains)) {
            throw new IllegalArgumentException("allow cannot exceed hostAllow");
        }
    }
}
