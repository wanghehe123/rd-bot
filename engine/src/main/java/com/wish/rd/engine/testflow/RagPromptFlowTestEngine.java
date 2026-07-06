package com.wish.rd.engine.testflow;

import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.rag.core.chunk.model.ChunkingMode;
import com.wish.rd.rag.ingestion.model.DocumentIngestionCommand;
import com.wish.rd.rag.ingestion.model.DocumentIngestionResult;
import com.wish.rd.rag.ingestion.DocumentIngestionService;
import com.wish.rd.rag.intent.model.IntentLevel;
import com.wish.rd.rag.intent.model.IntentNode;
import com.wish.rd.rag.intent.IntentTree;
import com.wish.rd.rag.pipeline.model.RepairContextPackage;
import com.wish.rd.rag.pipeline.RepairRagPipeline;
import com.wish.rd.rag.pipeline.model.RepairRagRequest;
import com.wish.rd.rag.prompt.model.RepairPromptPlan;
import com.wish.rd.rag.prompt.RepairPromptService;
import com.wish.rd.rag.rewrite.model.QueryTermMapping;
import com.wish.rd.rag.rewrite.impl.RuleBasedQueryRewriteService;
import com.wish.rd.rag.runtime.RagRuntimeFactory;
import com.wish.rd.rag.trace.model.RagTraceNodeRecord;
import com.wish.rd.rag.trace.model.RagTraceRunStart;
import com.wish.rd.rag.trace.RagTraceStore;
import com.wish.rd.rag.vector.impl.InMemoryVectorStore;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import com.wish.rd.engine.testflow.model.RagPromptFlowTestResult;

/**
 * Prompt 流程测试引擎：对应 {@code POST /test/rag/prompt-flow}。
 *
 * <p>在 full-flow 基础上额外演示"查询改写 + Prompt 规划"，并把每一步记录到
 * {@link RagTraceStore}，产出确定性 traceId {@code trace-ticket-prompt-flow}，供 /rag/traces 验证。
 *
 * <p>链路节点：ingestion → retrieval → rewrite → prompt，每个节点都 recordNode，
 * 最终 finishRun 并返回 traceId、Prompt 场景、改写后问题、子问题、段落、用户 Prompt 与证据 ID。
 */
public final class RagPromptFlowTestEngine {

    private final RagTraceStore traceStore;

    public RagPromptFlowTestEngine() {
        this(new RagTraceStore());
    }

    public RagPromptFlowTestEngine(RagTraceStore traceStore) {
        this.traceStore = traceStore == null ? new RagTraceStore() : traceStore;
    }

    public RagPromptFlowTestResult run() {
        String traceId = "trace-ticket-prompt-flow";
        long runStart = System.currentTimeMillis();
        // 开启一次链路追踪，记录入口方法与问题
        traceStore.startRun(new RagTraceRunStart(
                traceId,
                "prompt-flow",
                "POST /test/rag/prompt-flow",
                "conversation-test",
                "ticket-prompt-flow",
                "test-user",
                Map.of("question", "支付系统下单接口 500。金额为空怎么修复？")
        ));
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        long ingestionStart = System.currentTimeMillis();
        DocumentIngestionResult ingestionResult = DocumentIngestionService.inMemory(vectorStore).write(
                new DocumentIngestionCommand(
                        "payment-api.md",
                        "payment-system",
                        "api",
                        "text/markdown",
                        """
                        # 支付系统 API

                        POST /api/orders 是下单接口。
                        OrderService.create 写入订单前必须校验 orders.amount。
                        """.getBytes(StandardCharsets.UTF_8),
                        ChunkingMode.STRUCTURE_AWARE,
                        96,
                        12
                )
        );
        traceStore.recordNode(new RagTraceNodeRecord(
                traceId,
                "ingestion",
                null,
                0,
                "INGESTION",
                "document ingestion",
                "DocumentIngestionService",
                "write",
                "SUCCESS",
                null,
                elapsed(ingestionStart),
                Map.of("chunkCount", Integer.toString(ingestionResult.chunks().size()))
        ));
        IntentTree intentTree = new IntentTree(List.of(
                IntentNode.builder()
                        .id("payment-system")
                        .name("支付系统")
                        .description("支付、下单、订单、金额、orders.amount")
                        .level(IntentLevel.SYSTEM)
                        .systemId("payment-system")
                        .knowledgeBaseIds(List.of("payment-system"))
                        .codeRepositoryIds(List.of("payment-service"))
                        .examples(List.of("支付系统下单接口 500", "金额为空"))
                        .build()
        ));
        RepairRagPipeline pipeline = RagRuntimeFactory.repairRagPipeline(
                vectorStore,
                intentTree,
                query -> List.of("ERROR OrderService.create orders.amount is null"),
                query -> List.of(new RetrievedChunk(
                        "payment-service#OrderService.java#42",
                        "OrderService.create should validate amount before repository.save",
                        "payment-system",
                        "code-snippet",
                        "OrderService.java",
                        9.0d,
                        Map.of("repositoryId", query.repositoryId())
                )),
                context -> {}
        );
        RepairRagRequest request = new RepairRagRequest(
                "ticket-prompt-flow",
                "支付系统下单接口 500。金额为空怎么修复？",
                List.of("traceId=t-1")
        );
        long retrievalStart = System.currentTimeMillis();
        RepairContextPackage contextPackage = pipeline.prepareContext(request);
        traceStore.recordNode(new RagTraceNodeRecord(
                traceId,
                "retrieval",
                "ingestion",
                1,
                "RETRIEVAL",
                "repair context retrieval",
                "RepairRagPipeline",
                "prepareContext",
                "SUCCESS",
                null,
                elapsed(retrievalStart),
                Map.of("retrievedChunks", Integer.toString(contextPackage.retrievedChunks().size()))
        ));
        // 预置两条术语映射（下单→接口、金额→字段），演示改写效果
        RuleBasedQueryRewriteService rewriteService = new RuleBasedQueryRewriteService(List.of(
                new QueryTermMapping("下单", "POST /api/orders", 10, true),
                new QueryTermMapping("金额", "orders.amount", 9, true)
        ));
        long promptStart = System.currentTimeMillis();
        RepairPromptPlan promptPlan = RepairPromptService.defaultService(rewriteService).build(request, contextPackage);
        traceStore.recordNode(new RagTraceNodeRecord(
                traceId,
                "rewrite",
                "retrieval",
                2,
                "REWRITE",
                "query rewrite",
                "RuleBasedQueryRewriteService",
                "rewriteWithSplit",
                "SUCCESS",
                null,
                0,
                Map.of("rewrittenQuestion", promptPlan.rewrite().rewrittenQuestion())
        ));
        traceStore.recordNode(new RagTraceNodeRecord(
                traceId,
                "prompt",
                "rewrite",
                3,
                "PROMPT",
                "prompt plan",
                "RepairPromptService",
                "build",
                "SUCCESS",
                null,
                elapsed(promptStart),
                Map.of("scene", promptPlan.scene().name())
        ));
        traceStore.finishRun(traceId, "SUCCESS", null, elapsed(runStart));

        return new RagPromptFlowTestResult(
                traceId,
                ingestionResult.status(),
                promptPlan.scene().name(),
                promptPlan.rewrite().rewrittenQuestion(),
                promptPlan.rewrite().subQuestions(),
                promptPlan.promptSections(),
                promptPlan.userPrompt(),
                promptPlan.evidenceChunkIds()
        );
    }

    private long elapsed(long start) {
        return Math.max(0L, System.currentTimeMillis() - start);
    }
}
