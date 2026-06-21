package com.wish.rd.rag.ingestion;

import java.util.List;
import java.util.Objects;

public record PipelineDefinition(
        String id,
        String name,
        String description,
        List<NodeConfig> nodes
) {

    public PipelineDefinition {
        Objects.requireNonNull(id, "id must not be null");
        name = name == null ? id : name;
        description = description == null ? "" : description;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    public static PipelineDefinition defaultDocumentPipeline() {
        return new PipelineDefinition(
                "default-document-pipeline",
                "默认文档摄取管道",
                "Fetcher -> Parser -> Chunker -> Indexer",
                List.of(
                        new NodeConfig("fetcher", IngestionNodeType.FETCHER, "parser"),
                        new NodeConfig("parser", IngestionNodeType.PARSER, "chunker"),
                        new NodeConfig("chunker", IngestionNodeType.CHUNKER, "indexer"),
                        new NodeConfig("indexer", IngestionNodeType.INDEXER, null)
                )
        );
    }
}
