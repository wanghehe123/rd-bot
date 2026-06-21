package com.wish.rd.rag.ingestion;

public record IngestionPipelineNodeCommand(
        String nodeId,
        String nodeType,
        String nextNodeId
) {

    public IngestionPipelineNodeCommand {
        nodeId = nodeId == null ? "" : nodeId.strip();
        nodeType = nodeType == null ? "" : nodeType.strip().toUpperCase();
        nextNodeId = nextNodeId == null || nextNodeId.isBlank() ? null : nextNodeId.strip();
    }
}
