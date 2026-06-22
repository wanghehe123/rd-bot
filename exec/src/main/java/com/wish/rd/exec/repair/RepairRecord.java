package com.wish.rd.exec.repair;

import java.util.Map;
import java.util.Objects;

/**
 * 修复记录主实体，对应 {@code repair_records}。
 *
 * @param id                  主键 Snowflake ID
 * @param ticketId            工单 ID
 * @param ticketUrl           工单 URL
 * @param title               修复标题
 * @param status              当前状态
 * @param ragSummary          RAG 摘要
 * @param executorJson        执行器元数据 JSON
 * @param dockerJson          Docker 元数据 JSON
 * @param githubJson          GitHub 元数据 JSON
 * @param testJson            测试结果元数据 JSON
 * @param riskJson            风险元数据 JSON
 * @param errorMessage        错误信息
 * @param extensionJson       扩展字段
 * @param createdAtEpochMillis 创建时间
 * @param updatedAtEpochMillis 更新时间
 */
public record RepairRecord(
        String id,
        String ticketId,
        String ticketUrl,
        String title,
        RepairRecordStatus status,
        String ragSummary,
        String executorJson,
        String dockerJson,
        String githubJson,
        String testJson,
        String riskJson,
        String errorMessage,
        Map<String, String> extensionJson,
        long createdAtEpochMillis,
        long updatedAtEpochMillis
) {

    public RepairRecord {
        Objects.requireNonNull(id, "id must not be null");
        ticketId = ticketId == null ? "" : ticketId;
        ticketUrl = ticketUrl == null ? "" : ticketUrl;
        title = title == null ? "" : title;
        status = status == null ? RepairRecordStatus.CREATED : status;
        ragSummary = ragSummary == null ? "" : ragSummary;
        executorJson = normalizeJson(executorJson);
        dockerJson = normalizeJson(dockerJson);
        githubJson = normalizeJson(githubJson);
        testJson = normalizeJson(testJson);
        riskJson = normalizeJson(riskJson);
        errorMessage = errorMessage == null ? "" : errorMessage;
        extensionJson = extensionJson == null ? Map.of() : Map.copyOf(extensionJson);
    }

    public RepairRecord(
            String id,
            String ticketId,
            String ticketUrl,
            String title,
            RepairRecordStatus status,
            String ragSummary,
            Map<String, String> extensionJson,
            long createdAtEpochMillis,
            long updatedAtEpochMillis
    ) {
        this(
                id,
                ticketId,
                ticketUrl,
                title,
                status,
                ragSummary,
                "{}",
                "{}",
                "{}",
                "{}",
                "{}",
                "",
                extensionJson,
                createdAtEpochMillis,
                updatedAtEpochMillis
        );
    }

    public RepairRecord withStatus(RepairRecordStatus newStatus, String newRagSummary, long nowEpochMillis) {
        return new RepairRecord(
                id,
                ticketId,
                ticketUrl,
                title,
                newStatus,
                newRagSummary,
                executorJson,
                dockerJson,
                githubJson,
                testJson,
                riskJson,
                errorMessage,
                extensionJson,
                createdAtEpochMillis,
                nowEpochMillis
        );
    }

    public RepairRecord withExecutorJson(String newExecutorJson, long nowEpochMillis) {
        return withExecutionMetadata(newExecutorJson, dockerJson, githubJson, testJson, riskJson, errorMessage,
                nowEpochMillis);
    }

    public RepairRecord withDockerJson(String newDockerJson, long nowEpochMillis) {
        return withExecutionMetadata(executorJson, newDockerJson, githubJson, testJson, riskJson, errorMessage,
                nowEpochMillis);
    }

    public RepairRecord withGithubJson(String newGithubJson, long nowEpochMillis) {
        return withExecutionMetadata(executorJson, dockerJson, newGithubJson, testJson, riskJson, errorMessage,
                nowEpochMillis);
    }

    public RepairRecord withTestJson(String newTestJson, long nowEpochMillis) {
        return withExecutionMetadata(executorJson, dockerJson, githubJson, newTestJson, riskJson, errorMessage,
                nowEpochMillis);
    }

    public RepairRecord withRiskJson(String newRiskJson, long nowEpochMillis) {
        return withExecutionMetadata(executorJson, dockerJson, githubJson, testJson, newRiskJson, errorMessage,
                nowEpochMillis);
    }

    public RepairRecord withErrorMessage(String newErrorMessage, long nowEpochMillis) {
        return withExecutionMetadata(executorJson, dockerJson, githubJson, testJson, riskJson, newErrorMessage,
                nowEpochMillis);
    }

    private RepairRecord withExecutionMetadata(
            String newExecutorJson,
            String newDockerJson,
            String newGithubJson,
            String newTestJson,
            String newRiskJson,
            String newErrorMessage,
            long nowEpochMillis
    ) {
        return new RepairRecord(
                id,
                ticketId,
                ticketUrl,
                title,
                status,
                ragSummary,
                newExecutorJson,
                newDockerJson,
                newGithubJson,
                newTestJson,
                newRiskJson,
                newErrorMessage,
                extensionJson,
                createdAtEpochMillis,
                nowEpochMillis
        );
    }

    private static String normalizeJson(String value) {
        return RepairRecordJson.normalizeMetadataJson(value);
    }
}
