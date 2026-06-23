package com.wish.rd.engine;

import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.engine.rag.BugFixMessage;
import com.wish.rd.engine.rag.ChatQueueLimiter;
import com.wish.rd.engine.rag.RagBugFixEngine;
import com.wish.rd.engine.rag.RagRetrievalLogEvent;
import com.wish.rd.engine.rag.RagRetrievalLogSink;
import com.wish.rd.rag.intent.IntentTreeRegistry;
import com.wish.rd.rag.rewrite.QueryTermMappingRegistry;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagBugFixEngineTest {

    @Test
    void buildsBugFixMessageFromTicketFieldsForAgent() {
        RagBugFixEngine engine = new RagBugFixEngine(
                QueryTermMappingRegistry.withDefaults(),
                IntentTreeRegistry.withDefaults(),
                null,
                RagStreamTaskRegistry.inMemory(),
                ChatQueueLimiter.passThrough()
        );
        TicketSnapshot ticket = new TicketSnapshot(
                "ticket-payment-1",
                "支付系统下单接口 500",
                "金额为空时 OrderService.create 写入订单失败",
                List.of("payment", "orders.amount"),
                Instant.parse("2026-06-21T00:00:00Z")
        );

        BugFixMessage message = engine.findBugFixMessgaesForAgent(
                ticket,
                List.of("ERROR orders.amount is null at OrderService.create"),
                false
        );

        assertEquals("ticket-payment-1", message.ticketId());
        assertEquals("支付系统下单接口 500", message.ticketTitle());
        assertTrue(message.ticketDescription().contains("OrderService.create"));
        assertFalse(message.taskId().isBlank());
        assertEquals("payment-system", message.primaryIntentSystemId());
        assertTrue(message.searchChannels().contains("IntentDirectedVectorSearch"));
        assertTrue(message.searchChannels().contains("LogCenterSearch"));
        assertTrue(message.searchChannels().contains("CodeRepositorySearch"));
        assertFalse(message.retrievedChunks().isEmpty());
        assertTrue(message.contextSummary().contains("OrderService.create"));
        assertTrue(message.agentSystemMessage().contains("研发修复机器人"));
        assertTrue(message.agentUserMessage().contains("支付系统下单接口 500"));
        assertTrue(message.agentUserMessage().contains("ERROR orders.amount is null"));
        assertFalse(message.promptSections().contains("对话记忆"));
    }

    @Test
    void routesWaimaiOrderBugToWaimaiKnowledgeBase() {
        RagBugFixEngine engine = new RagBugFixEngine(
                QueryTermMappingRegistry.withDefaults(),
                IntentTreeRegistry.withDefaults(),
                null,
                RagStreamTaskRegistry.inMemory(),
                ChatQueueLimiter.passThrough()
        );
        TicketSnapshot ticket = new TicketSnapshot(
                "ticket-waimai-1",
                "外卖下单接口返回 500",
                """
                问题: 外卖下单接口返回 500
                系统: waimai
                优先级: P1
                日志: POST /api/orders 创建订单失败，客户端提交 delivery_address/remark 后服务端返回 500
                实际: 提交订单接口返回 500，下单失败
                期望: 订单创建成功并返回订单号
                """,
                List.of("feishu-im", "waimai"),
                Instant.parse("2026-06-23T08:04:32Z")
        );

        BugFixMessage message = engine.findBugFixMessgaesForAgent(
                ticket,
                List.of("POST /api/orders failed because required fields address phone customer_name are missing"),
                false
        );

        assertEquals("waimai", message.primaryIntentSystemId());
        assertEquals("外卖平台", message.primaryIntentName());
        assertTrue(message.contextSummary().contains("server/src/routes/orders.ts"));
        assertTrue(message.contextSummary().contains("delivery_address"));
    }

    @Test
    void publishesRetrievedChunksForEvaluationLogging() {
        RecordingRagRetrievalLogSink logSink = new RecordingRagRetrievalLogSink();
        RagBugFixEngine engine = new RagBugFixEngine(
                QueryTermMappingRegistry.withDefaults(),
                IntentTreeRegistry.withDefaults(),
                null,
                RagStreamTaskRegistry.inMemory(),
                ChatQueueLimiter.passThrough(),
                logSink
        );
        TicketSnapshot ticket = new TicketSnapshot(
                "ticket-payment-2",
                "支付系统下单接口 500",
                "金额为空时 OrderService.create 写入订单失败",
                List.of("payment", "orders.amount"),
                Instant.parse("2026-06-21T00:00:00Z")
        );

        BugFixMessage message = engine.findBugFixMessgaesForAgent(
                ticket,
                List.of("ERROR orders.amount is null at OrderService.create"),
                true,
                "task-rag-log-1"
        );

        assertEquals(1, logSink.events.size());
        RagRetrievalLogEvent event = logSink.events.getFirst();
        assertEquals("task-rag-log-1", event.taskId());
        assertEquals("ticket-payment-2", event.ticketId());
        assertEquals(message.contextSummary(), event.contextSummary());
        assertEquals(message.searchChannels(), event.searchChannels());
        assertEquals(message.retrievedChunks(), event.retrievedChunks());
        assertTrue(event.deepThinking());
        assertTrue(event.logs().contains("ERROR orders.amount is null at OrderService.create"));
        assertTrue(event.retrievedChunks().stream()
                .anyMatch(chunk -> chunk.content().contains("OrderService.create")));
    }

    private static final class RecordingRagRetrievalLogSink implements RagRetrievalLogSink {

        private final List<RagRetrievalLogEvent> events = new ArrayList<>();

        @Override
        public void append(RagRetrievalLogEvent event) {
            events.add(event);
        }
    }
}
