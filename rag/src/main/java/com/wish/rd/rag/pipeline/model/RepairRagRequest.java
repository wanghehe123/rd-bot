package com.wish.rd.rag.pipeline.model;

import java.util.List;
import java.util.Objects;

/**
 * 修复 RAG 请求入参。
 *
 * @param ticketId    工单/任务 ID（必填，用于链路追踪）
 * @param description 问题描述，允许为空字符串
 * @param logs        附带的日志片段列表，为空时取空列表
 * @param projectKnowledgeBaseIds 项目绑定的知识库范围，为空时沿用意图或全局范围
 */
public record RepairRagRequest(
        String ticketId,
        String description,
        List<String> logs,
        List<String> projectKnowledgeBaseIds
) {

    /** 紧凑构造器：强制 ticketId 非空，并把可空字段归一为安全默认值。 */
    public RepairRagRequest {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        description = description == null ? "" : description;
        logs = logs == null ? List.of() : List.copyOf(logs);
        projectKnowledgeBaseIds = projectKnowledgeBaseIds == null
                ? List.of()
                : projectKnowledgeBaseIds.stream().filter(id -> id != null && !id.isBlank()).map(String::strip).distinct().toList();
    }

    /**
     * 兼容尚未绑定项目知识库的既有 RAG 调用方。
     */
    public RepairRagRequest(String ticketId, String description, List<String> logs) {
        this(ticketId, description, logs, List.of());
    }
}
