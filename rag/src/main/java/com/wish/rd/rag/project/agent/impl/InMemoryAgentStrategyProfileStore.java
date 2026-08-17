package com.wish.rd.rag.project.agent.impl;

import com.wish.rd.rag.project.agent.AgentStrategyProfileStore;
import com.wish.rd.rag.project.agent.model.AgentStrategyProfile;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-memory strategy store for focused tests and the memory knowledge store. */
public final class InMemoryAgentStrategyProfileStore implements AgentStrategyProfileStore {

    private final ConcurrentMap<String, AgentStrategyProfile> profiles = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> defaults = new ConcurrentHashMap<>();

    @Override
    public AgentStrategyProfile save(AgentStrategyProfile profile) {
        profiles.put(key(profile.projectId(), profile.strategyId()), profile);
        return profile;
    }

    @Override
    public Optional<AgentStrategyProfile> find(String projectId, String strategyId) {
        return Optional.ofNullable(profiles.get(key(projectId, strategyId)));
    }

    @Override
    public List<AgentStrategyProfile> listByProject(String projectId) {
        String normalizedProject = normalized(projectId);
        return profiles.values().stream()
                .filter(profile -> profile.projectId().equals(normalizedProject))
                .sorted(Comparator.comparing(AgentStrategyProfile::name).thenComparing(AgentStrategyProfile::strategyId))
                .toList();
    }

    @Override
    public void bindDefault(String projectId, String strategyId) {
        defaults.put(normalized(projectId), normalized(strategyId));
    }

    @Override
    public Optional<String> findDefault(String projectId) {
        return Optional.ofNullable(defaults.get(normalized(projectId)));
    }

    private static String key(String projectId, String strategyId) {
        return normalized(projectId) + ":" + normalized(strategyId);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip();
    }
}
