package com.wish.rd.exec.repair.execution;

import java.util.List;
import java.util.Map;

/**
 * 修复执行结果，作为 Docker/Claude 执行器向上游编排和持久化适配器返回的规范载体。
 *
 * @param status             执行状态
 * @param summary            执行摘要
 * @param pullRequestUrl     PR 链接
 * @param artifacts          执行产物列表
 * @param rawResultJson      结构化结果 JSON 字段
 * @param dockerMetadataJson Docker 执行元数据
 * @param githubMetadataJson 代码平台元数据
 * @param testMetadataJson   测试结果元数据
 * @param riskMetadataJson   风险评估元数据
 * @param errorMessage       错误信息
 */
public record RepairExecutionResult(
        RepairExecutionStatus status,
        String summary,
        String pullRequestUrl,
        List<RepairArtifact> artifacts,
        Map<String, String> rawResultJson,
        Map<String, String> dockerMetadataJson,
        Map<String, String> githubMetadataJson,
        Map<String, String> testMetadataJson,
        Map<String, String> riskMetadataJson,
        String errorMessage
) {

    public RepairExecutionResult {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        summary = normalize(summary);
        pullRequestUrl = normalize(pullRequestUrl);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        rawResultJson = ExecutionJsonMaps.copy(rawResultJson);
        dockerMetadataJson = ExecutionJsonMaps.copy(dockerMetadataJson);
        githubMetadataJson = ExecutionJsonMaps.copy(githubMetadataJson);
        testMetadataJson = ExecutionJsonMaps.copy(testMetadataJson);
        riskMetadataJson = ExecutionJsonMaps.copy(riskMetadataJson);
        errorMessage = normalize(errorMessage);
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
