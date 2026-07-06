package com.wish.rd.rag.ingestion.model;

import java.util.List;

public record IngestionPipelineCommand(
        String name,
        String description,
        List<IngestionPipelineNodeCommand> nodes
) {

    public IngestionPipelineCommand {
        name = name == null || name.isBlank() ? "未命名摄取管道" : name.strip();
        description = description == null ? "" : description.strip();
        nodes = nodes == null || nodes.isEmpty()
                ? defaultNodes()
                : List.copyOf(nodes);
    }

    public static List<IngestionPipelineNodeCommand> defaultNodes() {
        return List.of(
                new IngestionPipelineNodeCommand("fetcher", "FETCHER", "parser"),
                new IngestionPipelineNodeCommand("parser", "PARSER", "chunker"),
                new IngestionPipelineNodeCommand("chunker", "CHUNKER", "indexer"),
                new IngestionPipelineNodeCommand("indexer", "INDEXER", null)
        );
    }
}
