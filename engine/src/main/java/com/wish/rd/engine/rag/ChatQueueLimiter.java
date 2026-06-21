package com.wish.rd.engine.rag;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Bug 修复聊天队列限流端口。
 *
 * <p>engine 只依赖该端口；Redis/Redisson 等具体排队实现由 bootstrap 适配。
 */
@FunctionalInterface
public interface ChatQueueLimiter {

    BugFixMessage enqueue(
            ChatQueueRequest request,
            Supplier<BugFixMessage> onAcquire,
            Supplier<BugFixMessage> onTimeout
    );

    static ChatQueueLimiter passThrough() {
        return (request, onAcquire, onTimeout) -> onAcquire.get();
    }

    static ChatQueueLimiter alwaysReject() {
        return (request, onAcquire, onTimeout) -> onTimeout.get();
    }

    record ChatQueueRequest(
            String question,
            String conversationId,
            String taskId
    ) {

        public ChatQueueRequest {
            question = question == null ? "" : question;
            conversationId = Objects.requireNonNull(conversationId, "conversationId must not be null");
            taskId = Objects.requireNonNull(taskId, "taskId must not be null");
        }
    }
}
