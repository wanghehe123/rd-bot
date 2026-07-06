package com.wish.rd.rag.ingestion.model;

import java.util.Objects;

public record NodeConfig(
        String nodeId,
        IngestionNodeType nodeType,
        String nextNodeId
) {

    public NodeConfig {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(nodeType, "nodeType must not be null");
        nextNodeId = nextNodeId == null || nextNodeId.isBlank() ? null : nextNodeId;
    }
}
