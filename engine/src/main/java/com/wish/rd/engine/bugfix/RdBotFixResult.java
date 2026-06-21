package com.wish.rd.engine.bugfix;

import com.wish.rd.engine.rag.BugFixMessage;
import com.wish.rd.rag.runtime.RdTaskStatus;

/**
 * RD 机器人修复流程结果。
 *
 * <p>供工单入口或测试入口读取任务 ID、状态、RAG 上下文与执行器结构化结果。
 *
 * @param taskId          任务 ID
 * @param status          当前任务状态
 * @param ragMessage      RAG 上下文消息
 * @param promptSnapshot  Prompt 快照
 * @param executionResult 执行器结果
 * @param rejected        是否被限流或流程拒绝
 */
public record RdBotFixResult(
        String taskId,
        RdTaskStatus status,
        BugFixMessage ragMessage,
        String promptSnapshot,
        BugFixExecutionResult executionResult,
        boolean rejected
) {

    public RdBotFixResult {
        taskId = taskId == null ? "" : taskId.strip();
        status = status == null ? RdTaskStatus.REJECTED : status;
        promptSnapshot = promptSnapshot == null ? "" : promptSnapshot;
        executionResult = executionResult == null ? BugFixExecutionResult.empty(taskId) : executionResult;
    }
}
