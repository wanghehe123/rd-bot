package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.rag.ingestion.ObjectStorageService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deletes private QA evidence when its owning task is logically deleted. */
@Component
public final class QaEvidenceRetentionService {

    private static final String PRIVATE_EVIDENCE_PREFIX = "s3://rd-qa-evidence/";
    private static final Set<String> QA_EVIDENCE_TYPES = Set.of(
            "QA_COMMAND_LOG",
            "QA_SCREENSHOT",
            "QA_TRACE",
            "QA_CONSOLE_LOG",
            "QA_NETWORK_LOG",
            "QA_HTTP_TRANSCRIPT",
            "QA_VIDEO",
            "QA_EVIDENCE_MANIFEST"
    );

    private final AgentStageArtifactStore artifactStore;
    private final ObjectStorageService objectStorageService;

    public QaEvidenceRetentionService(
            AgentStageArtifactStore artifactStore,
            ObjectStorageService objectStorageService
    ) {
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore must not be null");
        this.objectStorageService = Objects.requireNonNull(
                objectStorageService, "objectStorageService must not be null");
    }

    /**
     * Deletes every private QA object before removing its artifact rows.
     *
     * @return number of QA artifact rows selected for deletion
     */
    public int deleteForTask(String taskId) {
        String normalizedTaskId = taskId == null ? "" : taskId.strip();
        if (normalizedTaskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        List<AgentStageArtifact> artifacts = artifactStore.listByTask(normalizedTaskId).stream()
                .filter(artifact -> QA_EVIDENCE_TYPES.contains(artifact.artifactType()))
                .toList();
        for (AgentStageArtifact artifact : artifacts) {
            if (artifact.artifactUri().startsWith(PRIVATE_EVIDENCE_PREFIX)
                    && !objectStorageService.delete(artifact.artifactUri())) {
                throw new IllegalStateException("QA evidence object deletion was not acknowledged");
            }
        }
        artifactStore.deleteByTaskAndTypes(normalizedTaskId, QA_EVIDENCE_TYPES);
        return artifacts.size();
    }
}
