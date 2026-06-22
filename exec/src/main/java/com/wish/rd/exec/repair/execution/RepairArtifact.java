package com.wish.rd.exec.repair.execution;

import java.util.Map;

/**
 * 修复执行产物描述，供执行器返回文件位置并由后续持久化或代码平台适配器引用。
 *
 * @param type         产物类型
 * @param name         产物文件名或逻辑名
 * @param uri          产物 URI 或本地路径
 * @param summary      产物摘要
 * @param metadataJson 产物扩展元数据
 */
public record RepairArtifact(
        RepairArtifactType type,
        String name,
        String uri,
        String summary,
        Map<String, String> metadataJson
) {

    public RepairArtifact {
        type = type == null ? RepairArtifactType.OTHER : type;
        name = normalize(name);
        uri = normalize(uri);
        summary = normalize(summary);
        metadataJson = ExecutionJsonMaps.copy(metadataJson);
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
