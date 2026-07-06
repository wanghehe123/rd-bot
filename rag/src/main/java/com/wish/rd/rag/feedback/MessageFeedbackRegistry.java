package com.wish.rd.rag.feedback;

import com.wish.rd.rag.memory.impl.ConversationRegistry;
import com.wish.rd.rag.memory.model.ManagedConversationMessage;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Component;
import com.wish.rd.rag.feedback.model.MessageFeedback;
import com.wish.rd.rag.feedback.model.MessageFeedbackCommand;

@Component
public final class MessageFeedbackRegistry {

    private final ConversationRegistry conversationRegistry;
    private final LinkedHashMap<String, MessageFeedback> feedbackByUserAndMessage = new LinkedHashMap<>();

    public MessageFeedbackRegistry(ConversationRegistry conversationRegistry) {
        this.conversationRegistry = conversationRegistry == null ? ConversationRegistry.inMemory() : conversationRegistry;
    }

    public static MessageFeedbackRegistry inMemory(ConversationRegistry conversationRegistry) {
        return new MessageFeedbackRegistry(conversationRegistry);
    }

    public synchronized MessageFeedback submit(String messageId, String userId, MessageFeedbackCommand command) {
        String safeMessageId = requireText(messageId, "messageId must not be blank");
        String safeUserId = requireText(userId, "userId must not be blank");
        if (command == null || command.vote() == null || (command.vote() != 1 && command.vote() != -1)) {
            throw new IllegalArgumentException("vote must be 1 or -1");
        }
        ManagedConversationMessage message = conversationRegistry.findMessage(safeMessageId, safeUserId)
                .orElseThrow(() -> new NoSuchElementException("message not found: " + safeMessageId));
        if (!"assistant".equalsIgnoreCase(message.role())) {
            throw new IllegalArgumentException("only assistant messages can receive feedback");
        }
        long now = System.currentTimeMillis();
        String key = key(safeUserId, safeMessageId);
        MessageFeedback existing = feedbackByUserAndMessage.get(key);
        MessageFeedback feedback = new MessageFeedback(
                safeMessageId,
                message.conversationId(),
                safeUserId,
                command.vote(),
                safe(command.reason()),
                safe(command.comment()),
                existing == null ? now : existing.createTimeEpochMillis(),
                now
        );
        feedbackByUserAndMessage.put(key, feedback);
        conversationRegistry.updateMessageVote(safeMessageId, safeUserId, command.vote());
        return feedback;
    }

    public synchronized Map<String, Integer> userVotes(String userId, String... messageIds) {
        if (blank(userId) || messageIds == null || messageIds.length == 0) {
            return Map.of();
        }
        LinkedHashMap<String, Integer> votes = new LinkedHashMap<>();
        Arrays.stream(messageIds)
                .filter(messageId -> !blank(messageId))
                .forEach(messageId -> {
                    MessageFeedback feedback = feedbackByUserAndMessage.get(key(userId.strip(), messageId.strip()));
                    if (feedback != null) {
                        votes.put(messageId.strip(), feedback.vote());
                    }
                });
        return Map.copyOf(votes);
    }

    private String requireText(String value, String message) {
        if (blank(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    private String key(String userId, String messageId) {
        return userId + "::" + messageId;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
