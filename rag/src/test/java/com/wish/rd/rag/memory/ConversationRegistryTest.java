package com.wish.rd.rag.memory;

import com.wish.rd.framework.convention.ChatMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationRegistryTest {

    @Test
    void storesConversationMetadataAndMessagesThroughMemoryStoreContract() {
        ConversationRegistry registry = ConversationRegistry.inMemory();

        String userMessageId = registry.append(
                "conversation-a",
                "user-a",
                ChatMessage.user("支付系统下单接口 500，金额为空怎么修复？")
        );
        String assistantMessageId = registry.append(
                "conversation-a",
                "user-a",
                ChatMessage.assistant("检查 OrderService.create 的 orders.amount 校验。")
        );

        assertEquals("conversation-a#1", userMessageId);
        assertEquals("conversation-a#2", assistantMessageId);
        assertEquals(2, registry.loadHistory("conversation-a", "user-a").size());

        ManagedConversation conversation = registry.listConversations("user-a").getFirst();
        assertEquals("conversation-a", conversation.conversationId());
        assertTrue(conversation.title().contains("支付系统下单接口"));

        assertEquals("user", registry.listMessages("conversation-a", "user-a").get(0).role());
        assertEquals("assistant", registry.listMessages("conversation-a", "user-a").get(1).role());

        registry.rename("conversation-a", "user-a", "支付接口排障");
        assertEquals("支付接口排障", registry.listConversations("user-a").getFirst().title());

        registry.delete("conversation-a", "user-a");
        assertTrue(registry.listConversations("user-a").isEmpty());
        assertTrue(registry.listMessages("conversation-a", "user-a").isEmpty());
    }
}
