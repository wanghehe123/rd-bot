package com.wish.rd.exec.repair.code;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 代码评审请求创建结果。
 *
 * @param pullRequestUrl    评审请求访问地址
 * @param pullRequestNumber 评审请求编号
 * @param metadata          扩展元数据
 */
public record PullRequestResult(
        String pullRequestUrl,
        String pullRequestNumber,
        Map<String, String> metadata
) {

    public PullRequestResult {
        pullRequestUrl = normalize(pullRequestUrl);
        pullRequestNumber = normalize(pullRequestNumber);
        metadata = copyMetadata(metadata);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private static Map<String, String> copyMetadata(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copied = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalizedKey = normalize(key);
            if (normalizedKey.isBlank()) {
                throw new IllegalArgumentException("metadata key must not be blank");
            }
            if (copied.containsKey(normalizedKey)) {
                throw new IllegalArgumentException("metadata key must be unique after normalization: " + normalizedKey);
            }
            copied.put(normalizedKey, normalize(value));
        });
        return Map.copyOf(copied);
    }
}
