package com.wish.rd.engine.rag;

import java.util.List;

/**
 * V3 聊天流式结果载体。
 *
 * <p>承载一次 RAG V3 聊天的结果：会话 ID、任务 ID、是否深度思考标志、构造的
 * Prompt 段落、最终回答文本，以及原始 SSE 文本。供 V3 聊天接口组装响应/流输出。
 *
 * @param conversationId 会话 ID
 * @param taskId         任务 ID
 * @param deepThinking   是否启用深度思考
 * @param promptSections 构造的 Prompt 段落（不可变，null 归一为空列表）
 * @param answer         最终回答文本（null 归一为空串）
 * @param sseBody        原始 SSE 文本（null 归一为空串）
 */
public record RagV3ChatStreamResult(
        String conversationId,
        String taskId,
        boolean deepThinking,
        List<String> promptSections,
        String answer,
        String sseBody
) {

    public RagV3ChatStreamResult {
        promptSections = promptSections == null ? List.of() : List.copyOf(promptSections);
        answer = answer == null ? "" : answer;
        sseBody = sseBody == null ? "" : sseBody;
    }
}
