package com.wish.rd.exec.repair;

import java.util.Objects;

/**
 * 修复产物实体，对应 {@code repair_record_artifacts}。
 *
 * @param id                  主键 Snowflake ID
 * @param repairRecordId      修复记录 ID
 * @param artifactType        产物类型
 * @param artifactUri         产物 URI
 * @param summary             摘要
 * @param createdAtEpochMillis 创建时间
 */
public record RepairRecordArtifact(
        String id,
        String repairRecordId,
        String artifactType,
        String artifactUri,
        String summary,
        long createdAtEpochMillis
) {

    public RepairRecordArtifact {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(repairRecordId, "repairRecordId must not be null");
        artifactType = artifactType == null ? "" : artifactType;
        artifactUri = artifactUri == null ? "" : artifactUri;
        summary = summary == null ? "" : summary;
    }
}
