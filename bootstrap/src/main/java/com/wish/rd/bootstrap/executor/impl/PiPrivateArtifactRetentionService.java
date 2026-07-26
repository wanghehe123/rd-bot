package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.bootstrap.executor.PiAgentArtifactArchiveProperties;
import com.wish.rd.exec.repair.pi.AgentPrivateArtifactIndex;
import com.wish.rd.exec.repair.pi.model.AgentPrivateArtifactRecord;
import com.wish.rd.rag.ingestion.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** Idempotently removes expired restricted Pi objects and then their metadata rows. */
@Component
@ConditionalOnProperty(
        name = "rd.executor.pi.artifact-archive.enabled",
        havingValue = "true"
)
public final class PiPrivateArtifactRetentionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PiPrivateArtifactRetentionService.class);

    private final ObjectStorageService objectStorageService;
    private final AgentPrivateArtifactIndex privateArtifactIndex;
    private final PiAgentArtifactArchiveProperties properties;

    public PiPrivateArtifactRetentionService(
            ObjectStorageService objectStorageService,
            AgentPrivateArtifactIndex privateArtifactIndex,
            PiAgentArtifactArchiveProperties properties
    ) {
        this.objectStorageService = Objects.requireNonNull(
                objectStorageService, "objectStorageService must not be null");
        this.privateArtifactIndex = Objects.requireNonNull(
                privateArtifactIndex, "privateArtifactIndex must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        properties.validateForPublishing();
    }

    @Scheduled(fixedDelayString = "${rd.executor.pi.artifact-archive.cleanup-interval-millis:60000}")
    public void cleanupExpired() {
        List<AgentPrivateArtifactRecord> expired = privateArtifactIndex.findExpired(
                System.currentTimeMillis(),
                properties.getCleanupBatchSize()
        );
        for (AgentPrivateArtifactRecord record : expired) {
            try {
                if (objectStorageService.delete(record.artifactUri())) {
                    privateArtifactIndex.remove(record.artifactId());
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("Pi private artifact cleanup failed for {}", record.artifactId(), exception);
            }
        }
    }
}
