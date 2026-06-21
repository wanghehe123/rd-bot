package com.wish.rd.engine.testflow;

import java.util.List;

public record IngestionPipelineTestResult(
        String taskId,
        String pipelineId,
        String status,
        List<String> nodeTypes,
        int chunkCount,
        List<String> indexedChunkIds,
        String message
) {

    public IngestionPipelineTestResult {
        nodeTypes = nodeTypes == null ? List.of() : List.copyOf(nodeTypes);
        indexedChunkIds = indexedChunkIds == null ? List.of() : List.copyOf(indexedChunkIds);
    }
}
