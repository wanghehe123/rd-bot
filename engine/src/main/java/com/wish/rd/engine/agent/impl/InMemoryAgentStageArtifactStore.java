package com.wish.rd.engine.agent.impl;

import com.wish.rd.engine.agent.AgentStageArtifactStore;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import com.wish.rd.engine.agent.model.AgentStageArtifact;

/**
 * 内存阶段产物存储，用于本地开发和测试。
 */
public final class InMemoryAgentStageArtifactStore implements AgentStageArtifactStore {

    private final ConcurrentMap<String, AgentStageArtifact> artifacts = new ConcurrentHashMap<>();

    @Override
    public AgentStageArtifact save(AgentStageArtifact artifact) {
        artifacts.put(artifact.artifactId(), artifact);
        return artifact;
    }

    @Override
    public AgentStageArtifact saveImmutable(AgentStageArtifact artifact) {
        AgentStageArtifact existing = artifacts.get(artifact.artifactId());
        if (existing == null) {
            artifacts.put(artifact.artifactId(), artifact);
            return artifact;
        }
        AgentStageArtifactStore.assertImmutableCompatible(existing, artifact);
        return existing;
    }

    @Override
    public List<AgentStageArtifact> listByTask(String taskId) {
        String normalizedTaskId = taskId == null ? "" : taskId.strip();
        return artifacts.values().stream()
                .filter(artifact -> artifact.taskId().equals(normalizedTaskId))
                .sorted(Comparator
                        .comparing(AgentStageArtifact::createdAtEpochMillis)
                        .thenComparing(AgentStageArtifact::artifactId))
                .toList();
    }

    @Override
    public int deleteByTaskAndTypes(String taskId, Set<String> artifactTypes) {
        String normalizedTaskId = taskId == null ? "" : taskId.strip();
        Set<String> types = artifactTypes == null ? Set.of() : Set.copyOf(artifactTypes);
        int before = artifacts.size();
        artifacts.entrySet().removeIf(entry -> entry.getValue().taskId().equals(normalizedTaskId)
                && types.contains(entry.getValue().artifactType()));
        return before - artifacts.size();
    }
}
