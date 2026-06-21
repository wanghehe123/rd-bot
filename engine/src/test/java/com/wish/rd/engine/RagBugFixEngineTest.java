package com.wish.rd.engine;

import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.engine.rag.BugFixMessage;
import com.wish.rd.engine.rag.ChatQueueLimiter;
import com.wish.rd.engine.rag.RagBugFixEngine;
import com.wish.rd.rag.intent.IntentTreeRegistry;
import com.wish.rd.rag.rewrite.QueryTermMappingRegistry;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
        assertTrue(message.taskId().startsWith("task-"));
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
}
