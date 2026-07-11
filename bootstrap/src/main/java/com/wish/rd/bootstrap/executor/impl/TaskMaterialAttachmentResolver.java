package com.wish.rd.bootstrap.executor.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.execution.model.RepairInputAttachment;
import com.wish.rd.rag.ingestion.ObjectStorageService;
import com.wish.rd.rag.runtime.TaskMaterialStore;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;

/** Resolves object-backed task materials into isolated executor attachments. */
@Component
public final class TaskMaterialAttachmentResolver {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final TaskMaterialStore materialStore;
    private final ObjectStorageService objectStorageService;

    public TaskMaterialAttachmentResolver(
            TaskMaterialStore materialStore,
            ObjectStorageService objectStorageService
    ) {
        this.materialStore = materialStore;
        this.objectStorageService = objectStorageService;
    }

    public List<RepairInputAttachment> resolveByTask(String taskId) {
        return resolve(materialStore.listByTask(taskId));
    }

    public List<RepairInputAttachment> resolve(List<TaskMaterial> materials) {
        List<RepairInputAttachment> attachments = new ArrayList<>();
        for (TaskMaterial material : materials == null ? List.<TaskMaterial>of() : materials) {
            if (material.artifactUri().isBlank()) {
                continue;
            }
            try (InputStream input = objectStorageService.openStream(material.artifactUri())) {
                byte[] bytes = input.readAllBytes();
                verifyContentHash(material, bytes);
                attachments.add(new RepairInputAttachment(
                        material.materialId() + "-" + filename(material),
                        material.mimeType(),
                        bytes
                ));
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "task material attachment cannot be read: " + material.materialId(), exception);
            }
        }
        return List.copyOf(attachments);
    }

    private static void verifyContentHash(TaskMaterial material, byte[] bytes) {
        String expected = material.contentHash();
        if (expected == null || expected.isBlank()) {
            throw new IllegalStateException("task material content hash missing: " + material.materialId());
        }
        String actual;
        try {
            actual = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
        if (!actual.equalsIgnoreCase(expected.strip())) {
            throw new IllegalStateException("task material content hash mismatch: " + material.materialId());
        }
    }

    private String filename(TaskMaterial material) {
        try {
            String filename = OBJECT_MAPPER.readTree(material.metadataJson()).path("filename").asText("");
            if (!filename.isBlank()) {
                return filename;
            }
        } catch (Exception ignored) {
            // Fall through to the audited material title.
        }
        return material.title().isBlank() ? material.materialId() : material.title();
    }
}
