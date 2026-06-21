package com.wish.rd.engine.rag;

/**
 * Bug 修复 RAG 任务停止响应。
 */
public record BugFixStopResult(String taskId, String status) {

    public BugFixStopResult {
        taskId = taskId == null ? "" : taskId;
        status = status == null ? "" : status;
    }
}
