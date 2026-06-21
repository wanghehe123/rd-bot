package com.wish.rd.engine.admin.feedback;

import com.wish.rd.rag.feedback.MessageFeedback;
import com.wish.rd.rag.feedback.MessageFeedbackCommand;
import com.wish.rd.rag.feedback.MessageFeedbackRegistry;
import org.springframework.stereotype.Service;

/**
 * 消息反馈业务编排引擎。
 *
 * <p>委托 {@link MessageFeedbackRegistry} 处理消息点赞/点踩反馈
 * （registry 内校验投票仅针对助手消息并回写 vote）。供 {@code MessageFeedbackController} 调用。
 */
@Service
public final class MessageFeedbackAdminEngine {

    private final MessageFeedbackRegistry registry;

    public MessageFeedbackAdminEngine(MessageFeedbackRegistry registry) {
        this.registry = registry;
    }

    public MessageFeedback submit(String messageId, String userId, MessageFeedbackCommand command) {
        return registry.submit(messageId, userId, command);
    }
}
