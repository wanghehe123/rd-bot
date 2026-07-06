package com.wish.rd.rag.ingestion.model;

public record ManagedIngestionPipelineNode(
        String id,
        String nodeId,
        String nodeType,
        String nextNodeId
) {

    NodeConfig toNodeConfig() {
        return new NodeConfig(nodeId, IngestionNodeType.valueOf(nodeType), nextNodeId);
    }
}
