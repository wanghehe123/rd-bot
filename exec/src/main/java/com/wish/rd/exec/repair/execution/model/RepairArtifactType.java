package com.wish.rd.exec.repair.execution.model;

/**
 * 修复执行产物类型，用于区分结构化结果、补丁、日志和执行元数据等产物。
 */
public enum RepairArtifactType {
    RESULT_JSON,
    PATCH_DIFF,
    TEST_LOG,
    PROMPT_SNAPSHOT,
    DOCKER_METADATA,
    CLAUDE_EVENTS,
    STDOUT_LOG,
    STDERR_LOG,
    QA_COMMAND_LOG,
    QA_SCREENSHOT,
    QA_TRACE,
    QA_CONSOLE_LOG,
    QA_NETWORK_LOG,
    QA_HTTP_TRANSCRIPT,
    QA_VIDEO,
    QA_EVIDENCE_MANIFEST,
    OTHER
}
