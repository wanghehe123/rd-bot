package com.wish.rd.rag.memory;

import com.wish.rd.rag.memory.impl.DefaultConversationMemoryService;

import com.wish.rd.framework.convention.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationMemoryServiceTest {

    @Test
    void loadsHistoryBeforeAppendingCurrentMessage() {
        ConversationMemoryService memoryService = DefaultConversationMemoryService.inMemory();

        List<ChatMessage> firstHistory = memoryService.loadAndAppend(
                "conversation-1",
                "user-1",
                ChatMessage.user("支付系统下单接口 500")
        );
        memoryService.append(
                "conversation-1",
                "user-1",
                ChatMessage.assistant("已经定位到 OrderService.create 缺少 orders.amount 校验")
        );

        List<ChatMessage> secondHistory = memoryService.loadAndAppend(
                "conversation-1",
                "user-1",
                ChatMessage.user("刚才说的是哪个字段？")
        );

        assertTrue(firstHistory.isEmpty());
        assertEquals(2, secondHistory.size());
        assertEquals("user", secondHistory.get(0).role());
        assertEquals("assistant", secondHistory.get(1).role());
        assertEquals(3, memoryService.load("conversation-1", "user-1").size());
    }
}
