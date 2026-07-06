package com.wish.rd.rag.feedback;

import com.wish.rd.framework.convention.model.ChatMessage;
import com.wish.rd.rag.memory.impl.ConversationRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.wish.rd.rag.feedback.model.MessageFeedback;
import com.wish.rd.rag.feedback.model.MessageFeedbackCommand;

class MessageFeedbackRegistryTest {

    @Test
    void upsertsFeedbackOnlyForAssistantMessages() {
        ConversationRegistry conversationRegistry = ConversationRegistry.inMemory();
        String userMessageId = conversationRegistry.append("conversation-a", "user-a", ChatMessage.user("下单失败"));
        String assistantMessageId = conversationRegistry.append("conversation-a", "user-a", ChatMessage.assistant("检查金额校验"));
        MessageFeedbackRegistry feedbackRegistry = MessageFeedbackRegistry.inMemory(conversationRegistry);

        assertThrows(IllegalArgumentException.class, () -> feedbackRegistry.submit(
                userMessageId,
                "user-a",
                new MessageFeedbackCommand(1, "helpful", "user messages cannot be rated")
        ));

        MessageFeedback submitted = feedbackRegistry.submit(
                assistantMessageId,
                "user-a",
                new MessageFeedbackCommand(1, "helpful", "定位准确")
        );

        assertEquals(assistantMessageId, submitted.messageId());
        assertEquals("conversation-a", submitted.conversationId());
        assertEquals(1, submitted.vote());
        assertEquals(Map.of(assistantMessageId, 1), feedbackRegistry.userVotes("user-a", assistantMessageId));

        MessageFeedback updated = feedbackRegistry.submit(
                assistantMessageId,
                "user-a",
                new MessageFeedbackCommand(-1, "missing detail", "缺少代码位置")
        );

        assertEquals(-1, updated.vote());
        assertEquals("missing detail", updated.reason());
        assertEquals(Map.of(assistantMessageId, -1), feedbackRegistry.userVotes("user-a", assistantMessageId));
    }
}
