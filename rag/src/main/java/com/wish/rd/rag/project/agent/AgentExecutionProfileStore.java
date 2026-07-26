package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.model.AgentExecutionProfile;

import java.util.List;
import java.util.Optional;

/** Persistence port for registered profiles and their project/task bindings. */
public interface AgentExecutionProfileStore {

    AgentExecutionProfile save(AgentExecutionProfile profile);

    Optional<AgentExecutionProfile> find(String profileId);

    default List<AgentExecutionProfile> listByProject(String projectId) {
        return List.of();
    }

    void bindProjectDefault(String projectId, String role, String profileId);

    Optional<String> findProjectDefault(String projectId, String role);

    void setTaskOverride(String taskId, String projectId, String role, String profileId);

    Optional<String> findTaskOverride(String taskId, String role);

    default void clearTaskOverride(String taskId, String role) {
        throw new UnsupportedOperationException("task override deletion is not supported");
    }
}
