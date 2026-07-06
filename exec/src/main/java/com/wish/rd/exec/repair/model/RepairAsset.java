package com.wish.rd.exec.repair.model;

import com.wish.rd.exec.repair.RepairRecordJson;
import java.util.Objects;

/**
 * 修复资产实体，对应 {@code repair_assets}。
 *
 * @param id                   主键 Snowflake ID
 * @param repairRecordId       修复记录 ID
 * @param assetType            资产类型
 * @param title                标题
 * @param summary              摘要
 * @param contentJson          结构化内容 JSON
 * @param sourceArtifactId     来源产物 ID，可为空
 * @param reusable             是否可复用
 * @param createdAtEpochMillis 创建时间
 */
public record RepairAsset(
        String id,
        String repairRecordId,
        RepairAssetType assetType,
        String title,
        String summary,
        String contentJson,
        String sourceArtifactId,
        boolean reusable,
        long createdAtEpochMillis
) {

    public RepairAsset {
        Objects.requireNonNull(id, "id must not be null");
        repairRecordId = repairRecordId == null ? "" : repairRecordId;
        assetType = assetType == null ? RepairAssetType.OTHER : assetType;
        title = title == null ? "" : title;
        summary = summary == null ? "" : summary;
        contentJson = RepairRecordJson.normalizeMetadataJson(contentJson);
        sourceArtifactId = sourceArtifactId == null ? "" : sourceArtifactId;
    }
}
