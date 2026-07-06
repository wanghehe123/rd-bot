package com.wish.rd.engine.bugfix.acceptance.model;

import com.wish.rd.engine.rag.model.BugFixMessage;

/**
 * 验收计划生成命令。
 *
 * @param taskId     任务 ID
 * @param ragMessage RAG 上下文消息
 */
public record AcceptancePlanGenerationCommand(
        String taskId,
        BugFixMessage ragMessage
) {

    public AcceptancePlanGenerationCommand {
        taskId = taskId == null ? "" : taskId.strip();
    }

    public String ticketId() {
        return ragMessage == null ? "" : ragMessage.ticketId();
    }
}
