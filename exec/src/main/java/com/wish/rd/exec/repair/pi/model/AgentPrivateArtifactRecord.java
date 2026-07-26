package com.wish.rd.exec.repair.pi.model;

import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairArtifactType;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;

import java.util.Locale;

/** Restricted object metadata retained for access control and expiry cleanup. */
public record AgentPrivateArtifactRecord(
        String artifactId,
        String taskId,
        String stageRunId,
        String snapshotId,
        RepairArtifactType artifactType,
        String artifactName,
        String artifactUri,
        long bytes,
        String sha256,
        String contentType,
        String retentionClass,
        long createdAtEpochMillis,
        long expiresAtEpochMillis
) {

    public AgentPrivateArtifactRecord {
        artifactId = require(artifactId, "artifactId");
        taskId = require(taskId, "taskId");
        stageRunId = require(stageRunId, "stageRunId");
        snapshotId = require(snapshotId, "snapshotId");
        artifactType = artifactType == null ? RepairArtifactType.OTHER : artifactType;
        artifactName = require(artifactName, "artifactName");
        artifactUri = require(artifactUri, "artifactUri");
        if (!artifactUri.startsWith("s3://")) {
            throw new IllegalArgumentException("private artifact URI must be s3://");
        }
        if (bytes <= 0L) {
            throw new IllegalArgumentException("private artifact bytes must be positive");
        }
        sha256 = require(sha256, "sha256").toLowerCase(Locale.ROOT);
        contentType = require(contentType, "contentType");
        retentionClass = require(retentionClass, "retentionClass");
        if (createdAtEpochMillis <= 0L || expiresAtEpochMillis <= createdAtEpochMillis) {
            throw new IllegalArgumentException("private artifact retention timestamps are invalid");
        }
    }

    public static AgentPrivateArtifactRecord from(
            AgentExecutionProfileSnapshot snapshot,
            RepairArtifact artifact
    ) {
        if (snapshot == null || artifact == null) {
            throw new IllegalArgumentException("snapshot and artifact are required");
        }
        long now = System.currentTimeMillis();
        long expiresAt = parsePositive(artifact.metadataJson().get("expiresAtEpochMillis"));
        long bytes = parsePositive(artifact.metadataJson().get("bytes"));
        String digest = artifact.metadataJson().getOrDefault("sha256", artifact.uri());
        String id = "pi-private-" + AgentExecutionProfileSnapshot.sha256(
                snapshot.snapshotId() + "|" + artifact.type().name() + "|" + artifact.name() + "|" + artifact.uri()
        );
        return new AgentPrivateArtifactRecord(
                id,
                snapshot.taskId(),
                snapshot.stageRunId(),
                snapshot.snapshotId(),
                artifact.type(),
                artifact.name(),
                artifact.uri(),
                bytes,
                digest,
                artifact.metadataJson().getOrDefault("contentType", "application/octet-stream"),
                artifact.metadataJson().getOrDefault("retentionClass", "PI_PRIVATE"),
                now,
                expiresAt
        );
    }

    private static long parsePositive(String value) {
        try {
            long parsed = Long.parseLong(value == null ? "" : value.strip());
            if (parsed > 0L) return parsed;
        } catch (NumberFormatException ignored) {
            // normalized into the validation error below
        }
        throw new IllegalArgumentException("private artifact metadata contains an invalid positive number");
    }

    private static String require(String value, String name) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }
}
