package com.wish.rd.engine.testflow.model;

import java.util.List;

public record RagChatFlowTestResult(
        String conversationId,
        String firstAnswer,
        String secondAnswer,
        List<String> secondPromptSections,
        String secondUserPrompt,
        int historyMessageCount
) {

    public RagChatFlowTestResult {
        firstAnswer = firstAnswer == null ? "" : firstAnswer;
        secondAnswer = secondAnswer == null ? "" : secondAnswer;
        secondPromptSections = secondPromptSections == null ? List.of() : List.copyOf(secondPromptSections);
        secondUserPrompt = secondUserPrompt == null ? "" : secondUserPrompt;
    }
}
