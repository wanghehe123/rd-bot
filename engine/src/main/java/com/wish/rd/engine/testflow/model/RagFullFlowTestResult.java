package com.wish.rd.engine.testflow.model;

import java.util.List;

public record RagFullFlowTestResult(
        String documentStatus,
        List<String> nodeTypes,
        String guidanceAction,
        List<String> searchChannels,
        List<String> retrievedKnowledgeTypes,
        String summary
) {

    public RagFullFlowTestResult {
        nodeTypes = nodeTypes == null ? List.of() : List.copyOf(nodeTypes);
        searchChannels = searchChannels == null ? List.of() : List.copyOf(searchChannels);
        retrievedKnowledgeTypes = retrievedKnowledgeTypes == null ? List.of() : List.copyOf(retrievedKnowledgeTypes);
        summary = summary == null ? "" : summary;
    }
}
