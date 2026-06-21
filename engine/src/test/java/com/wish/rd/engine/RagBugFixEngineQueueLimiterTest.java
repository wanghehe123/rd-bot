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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagBugFixEngineQueueLimiterTest {

    @Test
    void routesBugFixMessageBuildThroughChatQueueLimiter() {
        AtomicReference<ChatQueueLimiter.ChatQueueRequest> queueRequest = new AtomicReference<>();
        ChatQueueLimiter limiter = (request, onAcquire, onTimeout) -> {
            queueRequest.set(request);
            return onAcquire.get();
        };
        RagBugFixEngine engine = new RagBugFixEngine(
                QueryTermMappingRegistry.withDefaults(),
                IntentTreeRegistry.withDefaults(),
                null,
                RagStreamTaskRegistry.inMemory(),
                limiter
        );
        TicketSnapshot ticket = new TicketSnapshot(
                "ticket-queue-1",
                "支付系统下单接口 500",
                "金额为空时 OrderService.create 写入订单失败",
                List.of("payment"),
                Instant.parse("2026-06-21T00:00:00Z")
        );

        BugFixMessage message = engine.findBugFixMessgaesForAgent(
                ticket,
                List.of("ERROR orders.amount is null at OrderService.create"),
                false
        );

        assertNotNull(queueRequest.get());
        assertTrue(queueRequest.get().taskId().startsWith("task-"));
        assertTrue(queueRequest.get().question().contains("OrderService.create"));
        assertFalse(message.rejected());
        assertEquals("ticket-queue-1", message.ticketId());
    }
}
