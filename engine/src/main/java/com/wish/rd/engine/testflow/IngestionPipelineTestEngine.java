package com.wish.rd.engine.testflow;

import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.rag.core.chunk.model.ChunkingMode;
import com.wish.rd.rag.ingestion.model.IngestionNodeLog;
import com.wish.rd.rag.ingestion.model.IngestionTaskCommand;
import com.wish.rd.rag.ingestion.model.IngestionTaskResult;
import com.wish.rd.rag.ingestion.model.PipelineDefinition;
import com.wish.rd.rag.ingestion.TaskIngestionEngine;
import com.wish.rd.rag.vector.impl.InMemoryVectorStore;

import java.nio.charset.StandardCharsets;
import com.wish.rd.engine.testflow.model.IngestionPipelineTestResult;

/**
 * 摄取管线测试引擎：对应 {@code POST /test/ingestion/default-pipeline}。
 *
 * <p>用默认文档管线（FETCHER→PARSER→CHUNKER→INDEXER）跑一遍支付系统 Markdown 文档的摄取，
 * 返回任务 ID、管线 ID、状态、节点类型、分块数与分块 ID，用于验证摄取链路端到端可用。
 */
public final class IngestionPipelineTestEngine {

    public IngestionPipelineTestResult runDefaultPipeline() {
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        // 用内存引擎执行默认文档管线，对一段 Markdown 做解析、结构感知分块与索引
        IngestionTaskResult result = TaskIngestionEngine.inMemory(vectorStore).execute(
                PipelineDefinition.defaultDocumentPipeline(),
                new IngestionTaskCommand(
                        "task-test-ingestion",
                        "payment-api.md",
                        "payment-system",
                        "api",
                        "text/markdown",
                        """
                        # 支付系统 API

                        POST /api/orders 是下单接口。
                        OrderService.create 负责校验 orders.amount。
                        当 orders.amount 为空时，下单接口返回 500。
                        """.getBytes(StandardCharsets.UTF_8),
                        ChunkingMode.STRUCTURE_AWARE,
                        72,
                        8
                )
        );
        return new IngestionPipelineTestResult(
                result.taskId(),
                result.pipelineId(),
                result.status().name(),
                result.nodeLogs().stream().map(IngestionNodeLog::nodeType).map(Enum::name).toList(),
                result.chunks().size(),
                result.chunks().stream().map(RetrievedChunk::chunkId).toList(),
                result.message()
        );
    }
}
