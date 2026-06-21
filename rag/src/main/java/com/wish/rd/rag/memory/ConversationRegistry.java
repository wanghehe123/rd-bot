package com.wish.rd.rag.memory;

import com.wish.rd.framework.convention.ChatMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 会话注册表：内存中管理会话与消息，同时实现 {@link ConversationMemoryStore}。
 *
 * <p>同时服务于两个场景：
 * <ul>
 *   <li>对话记忆：实现 {@link ConversationMemoryStore}，供 {@code RagV3ChatEngine}
 *       加载历史与追加消息；</li>
 *   <li>会话管理：支撑 /conversations 系列接口（列表、消息历史、重命名、删除、投票）。</li>
 * </ul>
 *
 * <p>会话与消息按 {@code userId::conversationId} 复合键隔离；所有读写均 synchronized 保证并发安全。
 * 消息 ID 形如 {@code conversationId#N}（URL 中 # 需编码）。
 */
@Component
public final class ConversationRegistry implements ConversationMemoryStore {

    /** 自动生成会话标题的最大长度。 */
    private static final int TITLE_MAX_LENGTH = 40;

    /** 会话集合，按复合键存储。 */
    private final LinkedHashMap<String, ManagedConversation> conversations = new LinkedHashMap<>();
    /** 消息集合，复合键→消息列表。 */
    private final LinkedHashMap<String, List<ManagedConversationMessage>> messages = new LinkedHashMap<>();

    public static ConversationRegistry inMemory() {
        return new ConversationRegistry();
    }

    /**
     * 加载会话历史，转为简洁的 {@link ChatMessage}（去掉 vote 等管理字段）。
     */
    @Override
    public synchronized List<ChatMessage> loadHistory(String conversationId, String userId) {
        return listMessages(conversationId, userId).stream()
                .map(message -> new ChatMessage(message.role(), message.content()))
                .toList();
    }

    /**
     * 追加一条消息：分配形如 {@code conversationId#N} 的 ID，并刷新会话的最近活跃时间。
     * 首条消息会自动用其内容生成会话标题。
     *
     * @return 新消息的 ID；参数非法时返回空串
     */
    @Override
    public synchronized String append(String conversationId, String userId, ChatMessage message) {
        if (blank(conversationId) || blank(userId) || message == null) {
            return "";
        }
        String key = key(conversationId, userId);
        List<ManagedConversationMessage> list = messages.computeIfAbsent(key, ignored -> new ArrayList<>());
        long now = System.currentTimeMillis();
        String id = conversationId + "#" + (list.size() + 1);
        list.add(new ManagedConversationMessage(
                id,
                conversationId,
                userId,
                message.role(),
                message.content(),
                null,
                null,
                null,
                now
        ));
        // 刷新会话元信息（首条消息时按内容生成标题）
        touchConversation(conversationId, userId, message, now);
        return id;
    }

    /** 列出某用户的所有会话，按最近活跃时间降序。 */
    public synchronized List<ManagedConversation> listConversations(String userId) {
        if (blank(userId)) {
            return List.of();
        }
        return conversations.values().stream()
                .filter(conversation -> conversation.userId().equals(userId))
                .sorted(Comparator.comparingLong(ManagedConversation::lastTime).reversed())
                .toList();
    }

    /** 列出指定会话的消息历史（按写入顺序）。 */
    public synchronized List<ManagedConversationMessage> listMessages(String conversationId, String userId) {
        if (blank(conversationId) || blank(userId)) {
            return List.of();
        }
        return List.copyOf(messages.getOrDefault(key(conversationId, userId), List.of()));
    }

    /** 按 ID 跨会话查找单条消息（用于反馈接口定位目标消息）。 */
    public synchronized Optional<ManagedConversationMessage> findMessage(String messageId, String userId) {
        if (blank(messageId) || blank(userId)) {
            return Optional.empty();
        }
        return messages.values().stream()
                .flatMap(List::stream)
                .filter(message -> message.id().equals(messageId))
                .filter(message -> message.userId().equals(userId))
                .findFirst();
    }

    /**
     * 更新消息投票（点赞/点踩）。定位到消息后整体替换为带 vote 的新版本。
     *
     * @throws NoSuchElementException 消息不存在时
     */
    public synchronized ManagedConversationMessage updateMessageVote(String messageId, String userId, Integer vote) {
        ManagedConversationMessage existing = findMessage(messageId, userId)
                .orElseThrow(() -> new NoSuchElementException("message not found: " + messageId));
        List<ManagedConversationMessage> list = messages.get(key(existing.conversationId(), userId));
        for (int index = 0; index < list.size(); index++) {
            ManagedConversationMessage message = list.get(index);
            if (message.id().equals(messageId)) {
                ManagedConversationMessage updated = new ManagedConversationMessage(
                        message.id(),
                        message.conversationId(),
                        message.userId(),
                        message.role(),
                        message.content(),
                        message.thinkingContent(),
                        message.thinkingDuration(),
                        vote,
                        message.createTime()
                );
                list.set(index, updated);
                return updated;
            }
        }
        throw new NoSuchElementException("message not found: " + messageId);
    }

    /** 重命名会话标题，标题不能为空。 */
    public synchronized ManagedConversation rename(String conversationId, String userId, String title) {
        if (blank(title)) {
            throw new IllegalArgumentException("conversation title must not be blank");
        }
        ManagedConversation existing = requireConversation(conversationId, userId);
        ManagedConversation renamed = new ManagedConversation(
                existing.conversationId(),
                existing.userId(),
                title.strip(),
                System.currentTimeMillis()
        );
        conversations.put(key(conversationId, userId), renamed);
        return renamed;
    }

    /** 删除会话及其全部消息。 */
    public synchronized void delete(String conversationId, String userId) {
        requireConversation(conversationId, userId);
        String key = key(conversationId, userId);
        conversations.remove(key);
        messages.remove(key);
    }

    /** 刷新会话元信息：首次出现时按消息内容生成标题，否则保留原标题并更新活跃时间。 */
    private void touchConversation(String conversationId, String userId, ChatMessage message, long now) {
        String key = key(conversationId, userId);
        ManagedConversation existing = conversations.get(key);
        String title = existing == null ? titleFrom(message) : existing.title();
        conversations.put(key, new ManagedConversation(conversationId, userId, title, now));
    }

    /** 校验会话存在，不存在抛异常。 */
    private ManagedConversation requireConversation(String conversationId, String userId) {
        ManagedConversation conversation = conversations.get(key(conversationId, userId));
        if (conversation == null) {
            throw new NoSuchElementException("conversation not found: " + conversationId);
        }
        return conversation;
    }

    /** 从首条消息内容生成会话标题，截断到 40 字，空内容用"新会话"。 */
    private String titleFrom(ChatMessage message) {
        String raw = message.content() == null ? "" : message.content().strip();
        if (raw.isBlank()) {
            return "新会话";
        }
        return raw.length() <= TITLE_MAX_LENGTH ? raw : raw.substring(0, TITLE_MAX_LENGTH);
    }

    /** 会话复合键：{@code userId::conversationId}，用于跨用户隔离。 */
    private String key(String conversationId, String userId) {
        return userId + "::" + conversationId;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
