package com.wish.rd.exec.repair.docker.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Claude Code 模型供应商配置。
 *
 * <p>该值对象只携带容器环境变量名和值。密钥值不应写入 {@code env}；
 * 密钥类变量使用空值表示从宿主机同名环境变量转发。
 *
 * @param name 供应商名称
 * @param env  容器环境变量
 */
public record ClaudeCodeModelProvider(String name, Map<String, String> env) {

    public ClaudeCodeModelProvider {
        name = requireText(name, "name");
        env = normalizeEnv(env);
    }

    /**
     * 默认 Anthropic API key 供应商，保持历史零配置行为。
     *
     * @return 默认 Anthropic provider
     */
    public static ClaudeCodeModelProvider defaultAnthropic() {
        return new ClaudeCodeModelProvider("anthropic", Map.of("ANTHROPIC_API_KEY", ""));
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static Map<String, String> normalizeEnv(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalizedKey = requireText(key, "env key");
            if (normalized.containsKey(normalizedKey)) {
                throw new IllegalArgumentException("env contains duplicate key after normalization: " + normalizedKey);
            }
            normalized.put(normalizedKey, value == null ? "" : value.strip());
        });
        return Map.copyOf(normalized);
    }
}
