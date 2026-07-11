package com.wish.rd.rag;

import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.adapter.model.CodeSearchQuery;
import com.wish.rd.adapter.model.LogQuery;
import com.wish.rd.rag.guidance.model.GuidanceDecision;
import com.wish.rd.rag.ingestion.model.DocumentSource;
import com.wish.rd.rag.ingestion.IngestionPipeline;
import com.wish.rd.rag.intent.model.IntentLevel;
import com.wish.rd.rag.intent.model.IntentNode;
import com.wish.rd.rag.intent.IntentTree;
import com.wish.rd.rag.pipeline.model.RepairContextPackage;
import com.wish.rd.rag.pipeline.RepairRagPipeline;
import com.wish.rd.rag.pipeline.model.RepairRagRequest;
import com.wish.rd.rag.pipeline.RepairTaskContextPort;
import com.wish.rd.rag.runtime.RagRuntimeFactory;
import com.wish.rd.rag.vector.impl.InMemoryVectorStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RepairRagPipelineMockTest {

    @Test
    void runsIntentGuidanceParallelRetrievalAndContextPackagingWithMockData() {
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        IngestionPipeline ingestionPipeline = RagRuntimeFactory.ingestionPipeline(vectorStore);

        ingestionPipeline.ingest(DocumentSource.systemDocument(
                "payment-architecture.md",
                "payment-system",
                "system-architecture",
                """
                支付系统架构说明
                下单接口会调用订单服务、库存服务和支付网关。
                订单服务负责写入订单表，并在异常时记录 order_id 与 stack trace。
                """.getBytes(StandardCharsets.UTF_8)
        ));
        ingestionPipeline.ingest(DocumentSource.systemDocument(
                "payment-api.md",
                "payment-system",
                "api",
                """
                支付系统 API
                POST /api/orders 是下单接口。
                当订单表字段缺失或金额格式错误时，下单接口可能返回 500。
                """.getBytes(StandardCharsets.UTF_8)
        ));
        ingestionPipeline.ingest(DocumentSource.systemDocument(
                "payment-schema.md",
                "payment-system",
                "database-schema",
                """
                数据库表
                orders 表包含 id、order_id、amount、status、created_at。
                amount 字段不能为空，status 只允许 CREATED、PAID、FAILED。
                """.getBytes(StandardCharsets.UTF_8)
        ));

        IntentTree intentTree = new IntentTree(List.of(
                IntentNode.builder()
                        .id("payment-system")
                        .name("支付系统")
                        .description("支付、下单、订单、支付网关、订单表")
                        .level(IntentLevel.SYSTEM)
                        .systemId("payment-system")
                        .codeRepositoryIds(List.of("payment-service"))
                        .knowledgeBaseIds(List.of("payment-system"))
                        .examples(List.of("支付系统下单接口 500", "订单表字段异常"))
                        .build(),
                IntentNode.builder()
                        .id("user-system")
                        .name("用户系统")
                        .description("登录、注册、用户资料")
                        .level(IntentLevel.SYSTEM)
                        .systemId("user-system")
                        .codeRepositoryIds(List.of("user-service"))
                        .knowledgeBaseIds(List.of("user-system"))
                        .examples(List.of("用户登录失败"))
                        .build()
        ));
        AtomicReference<RepairContextPackage> accepted = new AtomicReference<>();
        RepairTaskContextPort taskPort = accepted::set;
        RepairRagPipeline pipeline = RagRuntimeFactory.repairRagPipeline(vectorStore, intentTree, taskPort);

        RepairContextPackage result = pipeline.prepareContext(new RepairRagRequest(
                "ticket-1",
                "支付系统下单接口 500，日志里有 order_id，怀疑订单表字段异常",
                List.of("ERROR order_id=O1001 NullPointerException at OrderService.create")
        ));

        assertSame(result, accepted.get());
        assertEquals(GuidanceDecision.Action.NONE, result.guidanceDecision().action());
        assertTrue(result.primaryIntent().isPresent());
        assertEquals("payment-system", result.primaryIntent().get().node().id());
        assertTrue(result.retrievedChunks().stream().map(RetrievedChunk::knowledgeType).anyMatch("system-architecture"::equals));
        assertTrue(result.retrievedChunks().stream().map(RetrievedChunk::knowledgeType).anyMatch("api"::equals));
        assertTrue(result.retrievedChunks().stream().map(RetrievedChunk::knowledgeType).anyMatch("database-schema"::equals));
        assertTrue(result.searchChannels().contains("IntentDirectedVectorSearch"));
        assertTrue(result.searchChannels().contains("KeywordBM25Search"));
        assertFalse(result.retrievedChunks().isEmpty());
        assertTrue(result.summary().contains("支付系统"));
        assertTrue(result.summary().contains("orders"));
    }

    @Test
    void collectsLogsAndCodeRepositoryContextThroughAdapterPortsWhenIntentIsKnown() {
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        IngestionPipeline ingestionPipeline = RagRuntimeFactory.ingestionPipeline(vectorStore);
        ingestionPipeline.ingest(DocumentSource.systemDocument(
                "payment-runbook.md",
                "payment-system",
                "system-architecture",
                """
                支付系统排障手册
                下单接口 500 时优先检查 OrderService.create 与 orders.amount 字段。
                """.getBytes(StandardCharsets.UTF_8)
        ));
        IntentTree intentTree = new IntentTree(List.of(
                IntentNode.builder()
                        .id("payment-system")
                        .name("支付系统")
                        .description("支付、下单、订单、支付网关、订单表")
                        .level(IntentLevel.SYSTEM)
                        .systemId("payment-system")
                        .codeRepositoryIds(List.of("payment-service"))
                        .knowledgeBaseIds(List.of("payment-system"))
                        .examples(List.of("支付系统下单接口 500", "订单表字段异常"))
                        .build()
        ));
        AtomicReference<LogQuery> capturedLogQuery = new AtomicReference<>();
        AtomicReference<CodeSearchQuery> capturedCodeQuery = new AtomicReference<>();
        RepairRagPipeline pipeline = RagRuntimeFactory.repairRagPipeline(
                vectorStore,
                intentTree,
                query -> {
                    capturedLogQuery.set(query);
                    return List.of("ERROR payment-system order_id=O1001 amount is null at OrderService.create");
                },
                query -> {
                    capturedCodeQuery.set(query);
                    return List.of(new RetrievedChunk(
                            "payment-service#OrderService.java#42",
                            "OrderService.create validates orders.amount before writing database",
                            "payment-system",
                            "code-snippet",
                            "payment-service/OrderService.java",
                            9.0d,
                            Map.of("repositoryId", query.repositoryId())
                    ));
                },
                context -> {}
        );

        RepairContextPackage result = pipeline.prepareContext(new RepairRagRequest(
                "ticket-3",
                "支付系统下单接口 500，日志里有 order_id=O1001，怀疑 orders.amount 为空",
                List.of("traceId=t-1")
        ));

        assertNotNull(capturedLogQuery.get());
        assertEquals("payment-system", capturedLogQuery.get().systemId());
        assertNotNull(capturedCodeQuery.get());
        assertEquals("payment-service", capturedCodeQuery.get().repositoryId());
        assertTrue(result.searchChannels().contains("LogCenterSearch"));
        assertTrue(result.searchChannels().contains("CodeRepositorySearch"));
        assertTrue(result.retrievedChunks().stream().map(RetrievedChunk::knowledgeType).anyMatch("runtime-log"::equals));
        assertTrue(result.retrievedChunks().stream().map(RetrievedChunk::knowledgeType).anyMatch("code-snippet"::equals));
        assertTrue(result.summary().contains("OrderService.create"));
    }

    @Test
    void fallsBackToGlobalVectorSearchWhenIntentIsUnknown() {
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        IngestionPipeline ingestionPipeline = RagRuntimeFactory.ingestionPipeline(vectorStore);
        ingestionPipeline.ingest(DocumentSource.systemDocument(
                "inventory-api.md",
                "inventory-system",
                "api",
                """
                库存系统 API
                POST /api/stock/deduct 用于库存扣减。
                库存扣减异常时会返回 500，并记录 sku_id。
                """.getBytes(StandardCharsets.UTF_8)
        ));
        IntentTree intentTree = new IntentTree(List.of(
                IntentNode.builder()
                        .id("user-system")
                        .name("用户系统")
                        .description("登录、注册、用户资料")
                        .level(IntentLevel.SYSTEM)
                        .systemId("user-system")
                        .knowledgeBaseIds(List.of("user-system"))
                        .examples(List.of("用户登录失败"))
                        .build()
        ));
        RepairRagPipeline pipeline = RagRuntimeFactory.repairRagPipeline(vectorStore, intentTree, context -> {});

        RepairContextPackage result = pipeline.prepareContext(new RepairRagRequest(
                "ticket-4",
                "库存扣减接口 500，日志中有 sku_id",
                List.of()
        ));

        assertTrue(result.primaryIntent().isEmpty());
        assertTrue(result.searchChannels().contains("GlobalVectorSearch"));
        assertTrue(result.searchChannels().contains("KeywordBM25Search"));
        assertTrue(result.retrievedChunks().stream().map(RetrievedChunk::knowledgeBaseId).anyMatch("inventory-system"::equals));
        assertTrue(result.summary().contains("库存系统"));
    }

    @Test
    void scopesUnknownIntentRetrievalToProjectKnowledgeBase() {
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        IngestionPipeline ingestionPipeline = RagRuntimeFactory.ingestionPipeline(vectorStore);
        ingestionPipeline.ingest(DocumentSource.systemDocument(
                "waimai-order-flow.md",
                "waimai-kb",
                "code-snippet",
                """
                外卖订单由顾客创建后，商家接单、骑手接单并更新配送状态。
                订单接口位于 server/src/routes/orders.ts。
                """.getBytes(StandardCharsets.UTF_8)
        ));
        ingestionPipeline.ingest(DocumentSource.systemDocument(
                "inventory-order-flow.md",
                "inventory-kb",
                "code-snippet",
                """
                库存订单扣减完成后更新库存预留状态。
                订单接口位于 inventory/OrderReservationService.java。
                """.getBytes(StandardCharsets.UTF_8)
        ));
        IntentTree intentTree = new IntentTree(List.of(
                IntentNode.builder()
                        .id("user-system")
                        .name("用户系统")
                        .description("登录、注册、用户资料")
                        .level(IntentLevel.SYSTEM)
                        .knowledgeBaseIds(List.of("user-kb"))
                        .examples(List.of("用户登录失败"))
                        .build()
        ));
        RepairRagPipeline pipeline = RagRuntimeFactory.repairRagPipeline(vectorStore, intentTree, context -> {});

        RepairContextPackage result = pipeline.prepareContext(new RepairRagRequest(
                "ticket-project-kb",
                "订单接口状态异常，骑手无法更新配送",
                List.of(),
                List.of("waimai-kb")
        ));

        assertTrue(result.primaryIntent().isEmpty());
        assertTrue(result.searchChannels().contains("IntentDirectedVectorSearch"));
        assertFalse(result.searchChannels().contains("GlobalVectorSearch"));
        assertFalse(result.retrievedChunks().isEmpty());
        assertTrue(result.retrievedChunks().stream()
                .allMatch(chunk -> "waimai-kb".equals(chunk.knowledgeBaseId())));
    }

    @Test
    void promptsForMoreInformationWhenTicketIsAmbiguous() {
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        IntentTree intentTree = new IntentTree(List.of(
                IntentNode.builder()
                        .id("payment-system")
                        .name("支付系统")
                        .description("接口 500 超时 异常")
                        .level(IntentLevel.SYSTEM)
                        .knowledgeBaseIds(List.of("payment-system"))
                        .examples(List.of("接口 500"))
                        .build(),
                IntentNode.builder()
                        .id("order-system")
                        .name("订单系统")
                        .description("接口 500 超时 异常")
                        .level(IntentLevel.SYSTEM)
                        .knowledgeBaseIds(List.of("order-system"))
                        .examples(List.of("接口 500"))
                        .build()
        ));
        RepairRagPipeline pipeline = RagRuntimeFactory.repairRagPipeline(vectorStore, intentTree, context -> {});

        RepairContextPackage result = pipeline.prepareContext(new RepairRagRequest(
                "ticket-2",
                "接口 500，帮忙看一下",
                List.of()
        ));

        assertEquals(GuidanceDecision.Action.PROMPT, result.guidanceDecision().action());
        assertTrue(result.guidanceDecision().prompt().contains("支付系统"));
        assertTrue(result.guidanceDecision().prompt().contains("订单系统"));
        assertTrue(result.retrievedChunks().isEmpty());
    }

    @Test
    void retrievesProjectKnowledgeBaseWhenIntentIsAmbiguous() {
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        IngestionPipeline ingestionPipeline = RagRuntimeFactory.ingestionPipeline(vectorStore);
        ingestionPipeline.ingest(DocumentSource.systemDocument(
                "waimai-orders.md",
                "waimai-kb",
                "code-snippet",
                "外卖订单创建接口为 POST /api/orders，路由位于 server/src/routes/orders.ts。".getBytes(StandardCharsets.UTF_8)
        ));
        IntentTree intentTree = new IntentTree(List.of(
                IntentNode.builder()
                        .id("payment-system")
                        .name("支付系统")
                        .description("接口 500 超时 异常")
                        .level(IntentLevel.SYSTEM)
                        .knowledgeBaseIds(List.of("payment-system"))
                        .examples(List.of("接口 500"))
                        .build(),
                IntentNode.builder()
                        .id("order-system")
                        .name("订单系统")
                        .description("接口 500 超时 异常")
                        .level(IntentLevel.SYSTEM)
                        .knowledgeBaseIds(List.of("order-system"))
                        .examples(List.of("接口 500"))
                        .build()
        ));
        RepairRagPipeline pipeline = RagRuntimeFactory.repairRagPipeline(vectorStore, intentTree, context -> { });

        RepairContextPackage result = pipeline.prepareContext(new RepairRagRequest(
                "ticket-project-ambiguous",
                "接口 500，帮忙看一下",
                List.of(),
                List.of("waimai-kb")
        ));

        assertEquals(GuidanceDecision.Action.PROMPT, result.guidanceDecision().action());
        assertTrue(result.searchChannels().contains("IntentDirectedVectorSearch"));
        assertFalse(result.retrievedChunks().isEmpty());
        assertTrue(result.retrievedChunks().stream()
                .allMatch(chunk -> "waimai-kb".equals(chunk.knowledgeBaseId())));
    }
}
