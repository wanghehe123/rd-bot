package com.wish.rd.rag.ingestion;

import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.rag.core.chunk.ChunkingStrategyFactory;
import com.wish.rd.rag.core.chunk.model.VectorChunk;
import com.wish.rd.rag.core.chunk.strategy.impl.FixedSizeTextChunker;
import com.wish.rd.rag.core.chunk.strategy.impl.StructureAwareTextChunker;
import com.wish.rd.rag.core.parser.DocumentParser;
import com.wish.rd.rag.core.parser.DocumentParserSelector;
import com.wish.rd.rag.core.parser.impl.MarkdownDocumentParser;
import com.wish.rd.rag.core.parser.model.ParseResult;
import com.wish.rd.rag.core.parser.impl.PlainTextDocumentParser;
import com.wish.rd.rag.vector.VectorStore;

import java.util.List;
import com.wish.rd.rag.ingestion.model.DocumentIngestionCommand;
import com.wish.rd.rag.ingestion.model.DocumentIngestionResult;
import com.wish.rd.rag.ingestion.model.IngestionStatus;
import com.wish.rd.rag.ingestion.model.IngestionTaskCommand;
import com.wish.rd.rag.ingestion.model.IngestionTaskResult;
import com.wish.rd.rag.ingestion.model.PipelineDefinition;

public final class DocumentIngestionService {

    private final VectorStore vectorStore;
    private final DocumentParserSelector parserSelector;
    private final ChunkingStrategyFactory chunkingStrategyFactory;

    public DocumentIngestionService(
            VectorStore vectorStore,
            DocumentParserSelector parserSelector,
            ChunkingStrategyFactory chunkingStrategyFactory
    ) {
        this.vectorStore = vectorStore;
        this.parserSelector = parserSelector;
        this.chunkingStrategyFactory = chunkingStrategyFactory;
    }

    public static DocumentIngestionService inMemory(VectorStore vectorStore) {
        return new DocumentIngestionService(
                vectorStore,
                new DocumentParserSelector(List.of(new MarkdownDocumentParser(), new PlainTextDocumentParser())),
                new ChunkingStrategyFactory(List.of(new FixedSizeTextChunker(), new StructureAwareTextChunker()))
        );
    }

    public DocumentIngestionResult write(DocumentIngestionCommand command) {
        IngestionTaskResult result = new TaskIngestionEngine(
                vectorStore,
                parserSelector,
                chunkingStrategyFactory
        ).execute(
                PipelineDefinition.defaultDocumentPipeline(),
                new IngestionTaskCommand(
                        "task-inline",
                        command.sourceName(),
                        command.knowledgeBaseId(),
                        command.knowledgeType(),
                        command.mimeType(),
                        command.content(),
                        command.chunkingMode(),
                        command.chunkSize(),
                        command.overlapSize()
                )
        );
        return new DocumentIngestionResult(
                result.status() == IngestionStatus.COMPLETED ? "INDEXED" : result.status().name(),
                result.chunks(),
                result.nodeLogs()
        );
    }
}
