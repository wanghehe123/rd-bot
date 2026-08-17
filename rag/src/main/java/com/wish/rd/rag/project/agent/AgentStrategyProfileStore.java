package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.model.AgentStrategyProfile;

import java.util.List;
import java.util.Optional;

/** Persistence port for project-level agent strategies and the project default binding. */
public interface AgentStrategyProfileStore {

    /**
     * Saves a strategy header and replaces its role slots.
     *
     * @param profile complete four-role strategy
     * @return stored strategy
     */
    AgentStrategyProfile save(AgentStrategyProfile profile);

    /**
     * Finds one strategy owned by a project.
     *
     * @param projectId project id
     * @param strategyId strategy id
     * @return strategy when present
     */
    Optional<AgentStrategyProfile> find(String projectId, String strategyId);

    /**
     * Lists strategies for a project.
     *
     * @param projectId project id
     * @return strategies, possibly empty
     */
    List<AgentStrategyProfile> listByProject(String projectId);

    /**
     * Binds a project to one default strategy.
     *
     * @param projectId project id
     * @param strategyId strategy id
     */
    void bindDefault(String projectId, String strategyId);

    /**
     * Returns the default strategy id for a project.
     *
     * @param projectId project id
     * @return default strategy id when bound
     */
    Optional<String> findDefault(String projectId);
}
