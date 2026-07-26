package com.wish.rd.rag.project.agent.impl;

import com.wish.rd.rag.project.agent.AgentExecutionProfileStore;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfile;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-memory profile store for focused tests and local configurations. */
public final class InMemoryAgentExecutionProfileStore implements AgentExecutionProfileStore {

    private final ConcurrentMap<String, AgentExecutionProfile> profiles = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> projectDefaults = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> taskOverrides = new ConcurrentHashMap<>();

    @Override
    public AgentExecutionProfile save(AgentExecutionProfile profile) {
        profiles.put(profile.profileId(), profile);
        return profile;
    }

    @Override
    public Optional<AgentExecutionProfile> find(String profileId) {
        return Optional.ofNullable(profiles.get(normalized(profileId)));
    }

    @Override
    public List<AgentExecutionProfile> listByProject(String projectId) {
        String normalizedProject = normalized(projectId);
        return profiles.values().stream()
                .filter(profile -> profile.projectId().equals(normalizedProject))
                .sorted(Comparator.comparing(AgentExecutionProfile::role)
                        .thenComparing(AgentExecutionProfile::name)
                        .thenComparing(AgentExecutionProfile::profileId))
                .toList();
    }

    @Override
    public void bindProjectDefault(String projectId, String role, String profileId) {
        projectDefaults.put(projectKey(projectId, role), normalized(profileId));
    }

    @Override
    public Optional<String> findProjectDefault(String projectId, String role) {
        return Optional.ofNullable(projectDefaults.get(projectKey(projectId, role)));
    }

    @Override
    public void setTaskOverride(String taskId, String projectId, String role, String profileId) {
        taskOverrides.put(taskKey(taskId, projectId, role), normalized(profileId));
    }

    @Override
    public Optional<String> findTaskOverride(String taskId, String role) {
        String taskPrefix = normalized(taskId) + ":";
        String roleSuffix = ":" + normalized(role).toUpperCase(java.util.Locale.ROOT);
        return taskOverrides.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(taskPrefix) && entry.getKey().endsWith(roleSuffix))
                .map(java.util.Map.Entry::getValue)
                .findFirst();
    }

    @Override
    public void clearTaskOverride(String taskId, String role) {
        String taskPrefix = normalized(taskId) + ":";
        String roleSuffix = ":" + normalized(role).toUpperCase(java.util.Locale.ROOT);
        taskOverrides.keySet().removeIf(key -> key.startsWith(taskPrefix) && key.endsWith(roleSuffix));
    }

    private static String projectKey(String projectId, String role) {
        return normalized(projectId) + ":" + normalized(role).toUpperCase(java.util.Locale.ROOT);
    }

    private static String taskKey(String taskId, String projectId, String role) {
        return normalized(taskId) + ":" + projectKey(projectId, role);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip();
    }
}
