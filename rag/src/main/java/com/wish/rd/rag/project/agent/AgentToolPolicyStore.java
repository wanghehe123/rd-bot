package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.model.AgentToolPolicy;

import java.util.Optional;

/** Persistence boundary for immutable tool-policy versions. */
public interface AgentToolPolicyStore {

    AgentToolPolicy save(AgentToolPolicy policy);

    Optional<AgentToolPolicy> find(String policyId, long version);

    Optional<AgentToolPolicy> findLatest(String policyId);
}
