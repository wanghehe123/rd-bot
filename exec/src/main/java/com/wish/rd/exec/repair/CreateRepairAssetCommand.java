package com.wish.rd.exec.repair;

/**
 * 创建修复资产命令。
 *
 * @param repairRecordId   修复记录 ID
 * @param assetType        资产类型
 * @param title            标题
 * @param summary          摘要
 * @param contentJson      结构化内容 JSON
 * @param sourceArtifactId 来源产物 ID，可为空
 * @param reusable         是否可复用
 */
public record CreateRepairAssetCommand(
        String repairRecordId,
        RepairAssetType assetType,
        String title,
        String summary,
        String contentJson,
        String sourceArtifactId,
        boolean reusable
) {

    public CreateRepairAssetCommand {
        repairRecordId = repairRecordId == null ? "" : repairRecordId.strip();
        assetType = assetType == null ? RepairAssetType.OTHER : assetType;
        title = title == null ? "" : title.strip();
        summary = summary == null ? "" : summary.strip();
        contentJson = RepairRecordJson.normalizeMetadataJson(contentJson);
        sourceArtifactId = sourceArtifactId == null ? "" : sourceArtifactId.strip();
    }
}
