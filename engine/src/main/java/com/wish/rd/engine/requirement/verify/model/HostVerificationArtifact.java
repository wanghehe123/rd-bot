package com.wish.rd.engine.requirement.verify.model;

/**
 * Immutable metadata for one host-verification evidence object.
 *
 * <p>Logs live under the {@code verify-evidence/} convention. The store persists
 * this metadata; object bytes stay in object storage.
 *
 * @param artifactId            unique artifact id
 * @param taskId                owning RD task id
 * @param runId                 owning verification run id
 * @param artifactType          machine type such as {@code VERIFY_BUILD_LOG}
 * @param relativePath          path inside the task workspace or evidence prefix
 * @param objectUri             object-storage URI
 * @param contentType           MIME type
 * @param sizeBytes             byte length
 * @param sha256                hex SHA-256 of the stored bytes
 * @param createdAtEpochMillis  create time
 */
public record HostVerificationArtifact(
        String artifactId,
        String taskId,
        String runId,
        String artifactType,
        String relativePath,
        String objectUri,
        String contentType,
        long sizeBytes,
        String sha256,
        long createdAtEpochMillis
) {

    public HostVerificationArtifact {
        artifactId = requireText(artifactId, "artifactId");
        taskId = requireText(taskId, "taskId");
        runId = requireText(runId, "runId");
        artifactType = requireText(artifactType, "artifactType");
        relativePath = requireText(relativePath, "relativePath");
        objectUri = requireText(objectUri, "objectUri");
        contentType = contentType == null || contentType.isBlank() ? "text/plain" : contentType.strip();
        if (sizeBytes < 0L) {
            throw new IllegalArgumentException("sizeBytes must be >= 0");
        }
        sha256 = requireText(sha256, "sha256");
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
