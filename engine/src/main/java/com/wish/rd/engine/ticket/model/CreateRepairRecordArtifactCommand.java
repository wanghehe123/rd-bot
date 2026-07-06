package com.wish.rd.engine.ticket.model;

/**
 * 创建工单修复记录产物命令。
 *
 * @param repairRecordId 修复记录 ID
 * @param artifactType   产物类型
 * @param artifactUri    产物 URI
 * @param summary        摘要
 */
public record CreateRepairRecordArtifactCommand(
        String repairRecordId,
        String artifactType,
        String artifactUri,
        String summary
) {

    public CreateRepairRecordArtifactCommand {
        repairRecordId = repairRecordId == null ? "" : repairRecordId.strip();
        artifactType = artifactType == null ? "" : artifactType.strip();
        artifactUri = artifactUri == null ? "" : artifactUri.strip();
        summary = summary == null ? "" : summary.strip();
    }
}
