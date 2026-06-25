package com.wish.rd.engine.bugfix;

import com.wish.rd.engine.bugfix.acceptance.AcceptancePlan;
import com.wish.rd.engine.rag.BugFixMessage;

/**
 * Bug 修复执行器请求。
 *
 * <p>供 {@link BugFixExecutor} 接收 RAG 上下文和最终 Prompt，后续 Docker Claude Code 适配器实现该端口。
 *
 * @param taskId         任务 ID
 * @param prompt         执行 Prompt
 * @param ragMessage     RAG 上下文消息
 * @param acceptancePlan 已校验的验收计划
 */
public record BugFixExecutionRequest(
        String taskId,
        String prompt,
        BugFixMessage ragMessage,
        AcceptancePlan acceptancePlan
) {

    public BugFixExecutionRequest(String taskId, String prompt, BugFixMessage ragMessage) {
        this(taskId, prompt, ragMessage, AcceptancePlan.disabled(
                taskId,
                ragMessage == null ? "" : ragMessage.ticketId(),
                "acceptance planner is not configured"
        ));
    }

    public BugFixExecutionRequest {
        taskId = taskId == null ? "" : taskId.strip();
        prompt = prompt == null ? "" : prompt;
        acceptancePlan = acceptancePlan == null
                ? AcceptancePlan.disabled(taskId, ragMessage == null ? "" : ragMessage.ticketId(),
                "acceptance planner is not configured")
                : acceptancePlan;
    }
}
