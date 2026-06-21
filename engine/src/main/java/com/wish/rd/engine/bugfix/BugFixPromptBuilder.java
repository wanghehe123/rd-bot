package com.wish.rd.engine.bugfix;

import com.wish.rd.engine.rag.BugFixMessage;
import com.wish.rd.framework.convention.RetrievedChunk;

import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Bug 修复执行 Prompt 构建器。
 *
 * <p>供 {@link RdBotFixEngine} 将 RAG 上下文打包成稳定模板，后续执行器只消费纯文本 Prompt。
 */
@Component
public final class BugFixPromptBuilder {

    /**
     * 创建默认 Prompt 构建器。
     *
     * @return 默认构建器
     */
    public static BugFixPromptBuilder defaultBuilder() {
        return new BugFixPromptBuilder();
    }

    /**
     * 构建 Bug 修复执行 Prompt。
     *
     * @param message RAG 上下文消息
     * @return 执行 Prompt
     */
    public String build(BugFixMessage message) {
        BugFixMessage safeMessage = message == null
                ? emptyMessage()
                : message;
        return """
                # 研发修复任务

                ## 工单
                - 工单ID：%s
                - 标题：%s
                - 描述：%s
                - 标签：%s

                ## RAG 摘要
                %s

                ## 检索证据
                %s

                ## Agent 系统消息
                %s

                ## Agent 用户消息
                %s

                ## 输出要求
                请执行修复、自测，并输出结构化 JSON，至少包含 taskId、bugDescription、solution、pullRequestUrl、testSummary。
                """.formatted(
                safeMessage.ticketId(),
                safeMessage.ticketTitle(),
                safeMessage.ticketDescription(),
                String.join(",", safeMessage.ticketLabels()),
                safeMessage.contextSummary(),
                evidence(safeMessage),
                safeMessage.agentSystemMessage(),
                safeMessage.agentUserMessage()
        ).strip();
    }

    private String evidence(BugFixMessage message) {
        if (message.retrievedChunks().isEmpty()) {
            return "未检索到证据。";
        }
        return message.retrievedChunks().stream()
                .map(this::toEvidenceLine)
                .collect(Collectors.joining("\n"));
    }

    private String toEvidenceLine(RetrievedChunk chunk) {
        return "- [%s] %s".formatted(chunk.chunkId(), chunk.content());
    }

    private BugFixMessage emptyMessage() {
        return new BugFixMessage(
                "",
                "",
                "",
                java.util.List.of(),
                "",
                false,
                "",
                "",
                "",
                "",
                java.util.List.of(),
                java.util.List.of(),
                "",
                "",
                "",
                java.util.List.of(),
                java.util.List.of(),
                "",
                false
        );
    }
}
