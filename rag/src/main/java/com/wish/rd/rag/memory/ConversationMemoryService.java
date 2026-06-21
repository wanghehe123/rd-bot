package com.wish.rd.rag.memory;

import com.wish.rd.framework.convention.ChatMessage;

import java.util.List;

/**
 * 对话记忆服务接口：定义"加载历史"与"追加消息"两个核心能力。
 *
 * <p>供 {@code RagV3ChatEngine} 在生成每轮 Prompt 前加载历史、在生成回答后追加助手消息。
 * 默认实现 {@link DefaultConversationMemoryService} 用虚拟线程异步加载。
 */
public interface ConversationMemoryService {

    /**
     * 加载指定会话的历史消息（不含本轮）。
     *
     * @param conversationId 会话 ID
     * @param userId         用户 ID
     * @return 历史消息列表，按时间正序
     */
    List<ChatMessage> load(String conversationId, String userId);

    /**
     * 向会话追加一条消息，返回消息 ID。
     *
     * @return 新消息 ID；参数非法时返回空串
     */
    String append(String conversationId, String userId, ChatMessage message);

    /**
     * 加载历史并立即追加一条消息的复合操作，返回追加前的历史（即不含本次消息）。
     *
     * <p>这是聊天流程的典型用法：先把历史拼进 Prompt，再把当前用户消息写入记忆。
     * 注意返回的历史不含本次追加的消息，保证 Prompt 中历史与"当前问题"分离。
     */
    default List<ChatMessage> loadAndAppend(String conversationId, String userId, ChatMessage message) {
        List<ChatMessage> history = load(conversationId, userId);
        append(conversationId, userId, message);
        return history;
    }
}
