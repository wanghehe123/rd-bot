package com.wish.rd.engine.rag;

import com.wish.rd.framework.convention.RetrievedChunk;

import java.util.List;

/**
 * 提交给 Bug 修复 Agent 的 RAG 上下文消息。
 */
public record BugFixMessage(
        String ticketId,
        String ticketTitle,
        String ticketDescription,
        List<String> ticketLabels,
        String taskId,
        boolean deepThinking,
        String primaryIntentSystemId,
        String primaryIntentName,
        String guidanceAction,
        String guidancePrompt,
        List<String> searchChannels,
        List<RetrievedChunk> retrievedChunks,
        String contextSummary,
        String agentSystemMessage,
        String agentUserMessage,
        List<String> promptSections,
        List<String> evidenceChunkIds,
        String answer,
        boolean rejected
) {

    public BugFixMessage {
        ticketId = safe(ticketId);
        ticketTitle = safe(ticketTitle);
        ticketDescription = safe(ticketDescription);
        ticketLabels = ticketLabels == null ? List.of() : List.copyOf(ticketLabels);
        taskId = safe(taskId);
        primaryIntentSystemId = safe(primaryIntentSystemId);
        primaryIntentName = safe(primaryIntentName);
        guidanceAction = safe(guidanceAction);
        guidancePrompt = safe(guidancePrompt);
        searchChannels = searchChannels == null ? List.of() : List.copyOf(searchChannels);
        retrievedChunks = retrievedChunks == null ? List.of() : List.copyOf(retrievedChunks);
        contextSummary = safe(contextSummary);
        agentSystemMessage = safe(agentSystemMessage);
        agentUserMessage = safe(agentUserMessage);
        promptSections = promptSections == null ? List.of() : List.copyOf(promptSections);
        evidenceChunkIds = evidenceChunkIds == null ? List.of() : List.copyOf(evidenceChunkIds);
        answer = safe(answer);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
