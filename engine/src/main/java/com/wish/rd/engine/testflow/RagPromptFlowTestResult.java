package com.wish.rd.engine.testflow;

import java.util.List;

public record RagPromptFlowTestResult(
        String traceId,
        String documentStatus,
        String promptScene,
        String rewrittenQuestion,
        List<String> subQuestions,
        List<String> promptSections,
        String userPrompt,
        List<String> evidenceChunkIds
) {

    public RagPromptFlowTestResult {
        subQuestions = subQuestions == null ? List.of() : List.copyOf(subQuestions);
        promptSections = promptSections == null ? List.of() : List.copyOf(promptSections);
        userPrompt = userPrompt == null ? "" : userPrompt;
        evidenceChunkIds = evidenceChunkIds == null ? List.of() : List.copyOf(evidenceChunkIds);
    }
}
