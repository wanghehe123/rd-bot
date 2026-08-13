package com.wish.rd.rag.knowledge;

import com.wish.rd.rag.core.chunk.model.ChunkingMode;
import com.wish.rd.rag.ingestion.model.IngestionTaskCommand;
import com.wish.rd.rag.ingestion.model.PipelineDefinition;
import com.wish.rd.rag.knowledge.model.KnowledgeChunk;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentSource;
import com.wish.rd.rag.knowledge.model.WriteKnowledgeDocumentCommand;

import java.util.List;

/**
 * 知识文档 mutation 用例端口。生产 Controller/Importer/Scheduler 只依赖此端口，
 * 不得组合 Store 写入。
 */
public interface KnowledgeDocumentMutationPort {

    KnowledgeDocument writeDocument(WriteKnowledgeDocumentCommand command);

    KnowledgeDocument writeDocument(WriteKnowledgeDocumentCommand command, KnowledgeDocumentSource source);

    KnowledgeDocument writeDocument(PipelineDefinition pipeline, IngestionTaskCommand command);

    KnowledgeDocument writeDocumentIfChanged(WriteKnowledgeDocumentCommand command, KnowledgeDocumentSource source);

    KnowledgeDocument rechunkDocument(String documentId, ChunkingMode mode, int chunkSize, int overlapSize);

    void deleteDocument(String documentId);

    void deleteBase(String knowledgeBaseId);

    KnowledgeChunk createChunk(String documentId, String chunkId, int index, String content);

    KnowledgeChunk updateChunk(String documentId, String chunkId, String content);

    KnowledgeDocument setDocumentEnabled(String documentId, boolean enabled);

    KnowledgeDocument updateDocument(String documentId, String sourceName, String knowledgeType);

    boolean deleteChunk(String documentId, String chunkId);

    KnowledgeChunk setChunkEnabled(String chunkId, boolean enabled);

    int batchSetChunksEnabled(String documentId, List<String> chunkIds, boolean enabled);
}
