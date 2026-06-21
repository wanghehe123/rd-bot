package com.wish.rd.engine.admin.conversation;

import com.wish.rd.rag.memory.ConversationRegistry;
import com.wish.rd.rag.memory.ManagedConversation;
import com.wish.rd.rag.memory.ManagedConversationMessage;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 会话管理业务编排引擎。
 *
 * <p>委托 {@link ConversationRegistry} 完成按用户的会话列表、消息历史、重命名与删除；
 * 当未注入 registry 时回退到内存实现。供 {@code ConversationController} 调用。
 */
@Service
public final class ConversationAdminEngine {

    private final ConversationRegistry registry;

    public ConversationAdminEngine(ConversationRegistry registry) {
        this.registry = registry == null ? ConversationRegistry.inMemory() : registry;
    }

    public List<ManagedConversation> list(String userId) {
        return registry.listConversations(userId);
    }

    public List<ManagedConversationMessage> messages(String conversationId, String userId) {
        return registry.listMessages(conversationId, userId);
    }

    public ManagedConversation rename(String conversationId, String userId, String title) {
        return registry.rename(conversationId, userId, title);
    }

    public void delete(String conversationId, String userId) {
        registry.delete(conversationId, userId);
    }
}
