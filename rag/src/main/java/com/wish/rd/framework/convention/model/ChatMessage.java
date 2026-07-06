package com.wish.rd.framework.convention.model;

import java.util.Objects;

/**
 * 聊天消息约定：角色 + 内容，是对话记忆与 Prompt 段落的最小单元。
 *
 * <p>角色限定为 system / user / assistant 三种，构造时强制校验。
 * 提供 {@link #system(String)}、{@link #user(String)}、{@link #assistant(String)}
 * 三个工厂方法便于调用处构造。
 */
public record ChatMessage(String role, String content) {

    public ChatMessage {
        role = normalizeRole(role);
        content = content == null ? "" : content;
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    private static String normalizeRole(String role) {
        Objects.requireNonNull(role, "role must not be null");
        return switch (role.strip().toLowerCase()) {
            case "system", "user", "assistant" -> role.strip().toLowerCase();
            default -> throw new IllegalArgumentException("unsupported chat role: " + role);
        };
    }
}
