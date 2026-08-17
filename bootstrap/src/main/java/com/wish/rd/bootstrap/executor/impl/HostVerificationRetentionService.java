package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.engine.requirement.verify.HostVerificationStore;
import com.wish.rd.engine.requirement.verify.model.HostVerificationArtifact;
import com.wish.rd.rag.ingestion.ObjectStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deletes private host-verification evidence when its owning task is logically deleted.
 *
 * <p>Hooked next to {@link QaEvidenceRetentionService} from task DELETE.
 * File and s3 objects are removed before store rows so a failed object delete
 * does not leave an orphan URI.
 */
@Component
@ConditionalOnBean(HostVerificationStore.class)
public final class HostVerificationRetentionService {

    private final HostVerificationStore store;
    private final ObjectStorageService objectStorageService;

    /**
     * Creates the retention hook.
     *
     * @param store                verification metadata
     * @param objectStorageService s3-backed object store
     */
    public HostVerificationRetentionService(
            HostVerificationStore store,
            ObjectStorageService objectStorageService
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.objectStorageService = Objects.requireNonNull(
                objectStorageService, "objectStorageService must not be null");
    }

    /**
     * Deletes file/s3 objects for the task, then the artifact rows.
     *
     * @param taskId owning RD task id
     * @return number of artifact rows selected for deletion
     */
    public int deleteForTask(String taskId) {
        String normalizedTaskId = taskId == null ? "" : taskId.strip();
        if (normalizedTaskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        List<HostVerificationArtifact> artifacts = new ArrayList<>();
        for (var run : store.listByTask(normalizedTaskId)) {
            artifacts.addAll(store.listArtifacts(run.runId()));
        }
        for (HostVerificationArtifact artifact : artifacts) {
            deleteObject(artifact.objectUri());
        }
        store.deleteByTask(normalizedTaskId);
        return artifacts.size();
    }

    private void deleteObject(String objectUri) {
        String uri = objectUri == null ? "" : objectUri.strip();
        if (uri.startsWith("s3://")) {
            if (!objectStorageService.delete(uri)) {
                throw new IllegalStateException("host verification object deletion was not acknowledged");
            }
            return;
        }
        if (uri.startsWith("file:")) {
            try {
                Files.deleteIfExists(Path.of(URI.create(uri)));
            } catch (Exception exception) {
                throw new IllegalStateException("host verification file deletion failed: " + uri, exception);
            }
        }
    }
}
