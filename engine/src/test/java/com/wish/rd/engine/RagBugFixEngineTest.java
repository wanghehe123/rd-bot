package com.wish.rd.engine;

import com.wish.rd.adapter.model.TicketSnapshot;
import com.wish.rd.engine.rag.model.BugFixMessage;
import com.wish.rd.engine.rag.ChatQueueLimiter;
import com.wish.rd.engine.rag.RagBugFixEngine;
import com.wish.rd.engine.rag.model.RagRetrievalLogEvent;
import com.wish.rd.engine.rag.RagRetrievalLogSink;
import com.wish.rd.rag.intent.IntentTreeRegistry;
import com.wish.rd.rag.rewrite.QueryTermMappingRegistry;
import com.wish.rd.rag.rewrite.model.QueryTermMappingCommand;
import com.wish.rd.rag.rewrite.model.QueryTermMappingScope;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
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
        assertFalse(message.retrievedChunks().isEmpty());
        assertFalse(message.evidenceChunkIds().isEmpty());
        assertFalse(message.answer().contains("未检索到足够证据"));
        assertTrue(message.answer().contains("client/src/api.ts"), message.answer());
    }

    @Test
    void routesWaimaiPaymentCallbackBugToPaymentStatusEvidence() {
        RagBugFixEngine engine = new RagBugFixEngine(
                QueryTermMappingRegistry.withDefaults(),
                IntentTreeRegistry.withDefaults(),
                null,
                RagStreamTaskRegistry.inMemory(),
                ChatQueueLimiter.passThrough()
        );
        TicketSnapshot ticket = new TicketSnapshot(
                "ticket-waimai-payment-1",
                "外卖订单支付成功后状态仍为待支付",
                """
                问题: 外卖订单支付成功后状态仍为待支付
                系统: waimai
                优先级: P1
                日志: java.lang.IllegalStateException: Payment callback handled but order status remains PENDING_PAYMENT at com.waimai.payment.PaymentCallbackService.handleSuccess(PaymentCallbackService.java:67)
                实际: 用户支付成功后，订单详情仍显示待支付，商家无法接单
                期望: 支付成功后订单状态更新为 PAID，并通知商家接单
                """,
                List.of("feishu-im", "waimai", "P1"),
                Instant.parse("2026-06-25T04:00:00Z")
        );

        BugFixMessage message = engine.findBugFixMessgaesForAgent(
                ticket,
                List.of("PaymentCallbackService.handleSuccess completed but orders.status remains PENDING_PAYMENT"),
                false
        );

        assertEquals("waimai", message.primaryIntentSystemId());
        assertEquals("外卖平台", message.primaryIntentName());
        assertFalse(message.retrievedChunks().isEmpty());
        assertFalse(message.evidenceChunkIds().isEmpty());
        assertTrue(message.contextSummary().contains("PaymentCallbackService"));
        assertTrue(message.contextSummary().contains("PENDING_PAYMENT"));
        assertTrue(message.contextSummary().contains("PAID"));
        assertFalse(message.answer().contains("未检索到足够证据"));
        assertTrue(message.answer().contains("PaymentCallbackService.handleSuccess"));
    }

    @Test
    void usesProjectKnowledgeBaseForScopedRetrieval() {
        RagBugFixEngine engine = new RagBugFixEngine(
                QueryTermMappingRegistry.withDefaults(),
                IntentTreeRegistry.withDefaults(),
                null,
                RagStreamTaskRegistry.inMemory(),
                ChatQueueLimiter.passThrough()
        );
        TicketSnapshot ticket = new TicketSnapshot(
                "ticket-project-scope-1",
                "移动端页面白屏",
                "进入商家中心后页面无法渲染，需要排查项目代码。",
                List.of("mobile", "frontend"),
                Instant.parse("2026-07-10T00:00:00Z")
        );

        BugFixMessage message = engine.findBugFixMessgaesForAgent(
                ticket,
                List.of("TypeError: Cannot read properties of undefined"),
                false,
                "task-project-scope-1",
                List.of("waimai")
        );

        assertTrue(message.searchChannels().contains("IntentDirectedVectorSearch"));
        assertFalse(message.searchChannels().contains("GlobalVectorSearch"));
        assertFalse(message.retrievedChunks().isEmpty());
        assertTrue(message.retrievedChunks().stream()
                .allMatch(chunk -> "waimai".equals(chunk.knowledgeBaseId())));
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

    @Test
    void appliesOnlyTheTaskProjectRulesWhenBuildingBugFixPrompt() {
        QueryTermMappingRegistry mappings = QueryTermMappingRegistry.inMemory();
        mappings.create(new QueryTermMappingCommand(
                "", QueryTermMappingScope.GLOBAL, "金额", "orders.amount", 1, true, "global"
        ));
        mappings.create(new QueryTermMappingCommand(
                "project-1", QueryTermMappingScope.PROJECT, "下单", "POST /p1/orders", 1, true, "project one"
        ));
        mappings.create(new QueryTermMappingCommand(
                "project-2", QueryTermMappingScope.PROJECT, "下单", "POST /p2/orders", 1, true, "project two"
        ));
        RagStreamTaskRegistry tasks = RagStreamTaskRegistry.inMemory();
        RdBugFixTask projectOneTask = tasks.createTaskManually(
                "ticket-project-1", "project one", "project one", "P2", "",
                "project-1", "p1", "Project One", "", "", "", "main"
        );
        RdBugFixTask projectTwoTask = tasks.createTaskManually(
                "ticket-project-2", "project two", "project two", "P2", "",
                "project-2", "p2", "Project Two", "", "", "", "main"
        );
        RdBugFixTask globalOnlyTask = tasks.createBugFixTask(new TicketSnapshot(
                "ticket-global", "global", "", List.of(), Instant.now()), "P2");
        RagBugFixEngine engine = new RagBugFixEngine(
                mappings, IntentTreeRegistry.withDefaults(), null, tasks, ChatQueueLimiter.passThrough()
        );
        TicketSnapshot ticket = new TicketSnapshot(
                "ticket-project-rules", "下单金额错误", "下单金额错误", List.of(), Instant.now()
        );

        BugFixMessage projectOne = engine.findBugFixMessgaesForAgent(
                ticket, List.of(), false, projectOneTask.taskId());
        BugFixMessage projectTwo = engine.findBugFixMessgaesForAgent(
                ticket, List.of(), false, projectTwoTask.taskId());
        BugFixMessage globalOnly = engine.findBugFixMessgaesForAgent(
                ticket, List.of(), false, globalOnlyTask.taskId());

        assertTrue(projectOne.agentUserMessage().contains("POST /p1/ordersorders.amount"));
        assertFalse(projectOne.agentUserMessage().contains("POST /p2/orders"));
        assertTrue(projectTwo.agentUserMessage().contains("POST /p2/ordersorders.amount"));
        assertFalse(projectTwo.agentUserMessage().contains("POST /p1/orders"));
        assertTrue(globalOnly.agentUserMessage().contains("下单orders.amount"));
        assertFalse(globalOnly.agentUserMessage().contains("POST /p1/orders"));
        assertFalse(globalOnly.agentUserMessage().contains("POST /p2/orders"));
    }

    private static final class RecordingRagRetrievalLogSink implements RagRetrievalLogSink {

        private final List<RagRetrievalLogEvent> events = new ArrayList<>();

        @Override
        public void append(RagRetrievalLogEvent event) {
            events.add(event);
        }
    }
}
