package com.wish.rd.engine.testflow;

import com.wish.rd.framework.convention.RetrievedChunk;
import com.wish.rd.rag.core.chunk.ChunkingMode;
import com.wish.rd.rag.ingestion.DocumentIngestionCommand;
import com.wish.rd.rag.ingestion.DocumentIngestionResult;
import com.wish.rd.rag.ingestion.DocumentIngestionService;
import com.wish.rd.rag.intent.IntentLevel;
import com.wish.rd.rag.intent.IntentNode;
import com.wish.rd.rag.intent.IntentTree;
import com.wish.rd.rag.pipeline.RepairContextPackage;
import com.wish.rd.rag.pipeline.RepairRagPipeline;
import com.wish.rd.rag.pipeline.RepairRagRequest;
import com.wish.rd.rag.runtime.RagRuntimeFactory;
import com.wish.rd.rag.vector.InMemoryVectorStore;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 全流程测试引擎：对应 {@code POST /test/rag/full-flow}。
 *
 * <p>用固定的 mock 数据演示 RAG 完整链路：
 * <ol>
 *   <li>种入支付系统文档（含"金额为空返回 500"的线索）；</li>
 *   <li>构建单节点意图树（支付系统，绑定知识库与代码仓库）；</li>
 *   <li>用 {@link RagRuntimeFactory} 装配带日志/代码端口的完整修复管线；</li>
 *   <li>对一条确定性工单跑 {@code prepareContext}，返回摄取状态、节点类型、
 *       歧义判定、检索通道、证据类型与上下文摘要。</li>
 * </ol>
 *
 * <p>主要用于本地冒烟验证：确认摄取、意图分类、多通道检索、上下文打包都能端到端跑通。
 */
public final class RagFullFlowTestEngine {

    public RagFullFlowTestResult run() {
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        DocumentIngestionService ingestionService = DocumentIngestionService.inMemory(vectorStore);
        // 种入支付系统 API 文档，演示结构感知分块
        DocumentIngestionResult ingestionResult = ingestionService.write(new DocumentIngestionCommand(
                "payment-api.md",
                "payment-system",
                "api",
                "text/markdown",
                """
                # 支付系统 API

                POST /api/orders 是下单接口。
                当 orders.amount 为空时，下单接口返回 500。

                ## 排障

                优先检查 OrderService.create 的入参校验和订单表写入逻辑。
                """.getBytes(StandardCharsets.UTF_8),
                ChunkingMode.STRUCTURE_AWARE,
                96,
                12
        ));

        // 单节点意图树：支付系统，绑定知识库与代码仓库，便于触发意图导向/日志/代码检索通道
        IntentTree intentTree = new IntentTree(List.of(
                IntentNode.builder()
                        .id("payment-system")
                        .name("支付系统")
                        .description("支付、下单、订单、订单表、支付网关")
                        .level(IntentLevel.SYSTEM)
                        .systemId("payment-system")
                        .codeRepositoryIds(List.of("payment-service"))
                        .knowledgeBaseIds(List.of("payment-system"))
                        .examples(List.of("支付系统下单接口 500", "订单表字段异常"))
                        .build()
        ));
        // 装配完整管线：注入 mock 的日志端口（返回 amount 为空错误）与代码端口（返回校验片段）
        RepairRagPipeline pipeline = RagRuntimeFactory.repairRagPipeline(
                vectorStore,
                intentTree,
                query -> List.of("ERROR payment-system order_id=O1001 orders.amount is null at OrderService.create"),
                query -> List.of(new RetrievedChunk(
                        "payment-service#OrderService.java#42",
                        "OrderService.create validates orders.amount before writing database",
                        "payment-system",
                        "code-snippet",
                        "payment-service/OrderService.java",
                        9.0d,
                        Map.of("repositoryId", query.repositoryId())
                )),
                context -> {}
        );
        // 对确定性工单执行主流程
        RepairContextPackage context = pipeline.prepareContext(new RepairRagRequest(
                "ticket-full-flow",
                "支付系统下单接口 500，日志里有 order_id，怀疑 orders.amount 为空",
                List.of("traceId=t-1")
        ));

        return new RagFullFlowTestResult(
                ingestionResult.status(),
                ingestionResult.nodeLogs().stream().map(log -> log.nodeType().name()).toList(),
                context.guidanceDecision().action().name(),
                context.searchChannels(),
                context.retrievedChunks().stream().map(RetrievedChunk::knowledgeType).distinct().toList(),
                context.summary()
        );
    }
}
