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

    /**
     * 将请求放入限流队列，拿到执行权后调用 {@code onAcquire}，超时则调用 {@code onTimeout}。
     *
     * @param request   队列请求
     * @param onAcquire 获得执行权后的回调
     * @param onTimeout 等待超时后的回调
     * @return 回调结果
     */
    <T> T enqueue(
            ChatQueueRequest request,
            Supplier<T> onAcquire,
            Supplier<T> onTimeout
    );

    /**
     * 创建直接放行的限流器。
     *
     * @return 直接执行 {@code onAcquire} 的限流器
     */
    static ChatQueueLimiter passThrough() {
        return new ChatQueueLimiter() {
            @Override
            public <T> T enqueue(ChatQueueRequest request, Supplier<T> onAcquire, Supplier<T> onTimeout) {
                return onAcquire.get();
            }
        };
    }

    /**
     * 创建永远拒绝的限流器。
     *
     * @return 直接执行 {@code onTimeout} 的限流器
     */
    static ChatQueueLimiter alwaysReject() {
        return new ChatQueueLimiter() {
            @Override
            public <T> T enqueue(ChatQueueRequest request, Supplier<T> onAcquire, Supplier<T> onTimeout) {
                return onTimeout.get();
            }
        };
    }

    record ChatQueueRequest(
            String question,
            String taskId,
            String priority
    ) {

        public ChatQueueRequest(String question, String taskId) {
            this(question, taskId, "P2");
        }

        public ChatQueueRequest {
            question = question == null ? "" : question;
            taskId = Objects.requireNonNull(taskId, "taskId must not be null");
            priority = priority == null || priority.isBlank() ? "P2" : priority.strip().toUpperCase();
        }
    }
}
