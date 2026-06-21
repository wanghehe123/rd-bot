package com.wish.rd.rag.ingestion;

import java.util.List;

public record ManagedIngestionPipeline(
        String id,
        String name,
        String description,
        String createdBy,
        List<ManagedIngestionPipelineNode> nodes,
        long createTimeEpochMillis,
        long updateTimeEpochMillis
) {

    public ManagedIngestionPipeline {
        description = description == null ? "" : description;
        createdBy = createdBy == null || createdBy.isBlank() ? "local" : createdBy;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    PipelineDefinition toPipelineDefinition() {
        return new PipelineDefinition(
                id,
                name,
                description,
                nodes.stream().map(ManagedIngestionPipelineNode::toNodeConfig).toList()
        );
    }
}
