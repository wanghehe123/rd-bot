package com.wish.rd.bootstrap.feishu;

import com.wish.rd.rag.knowledge.FeishuDocumentClient;
import com.wish.rd.rag.knowledge.FeishuDocumentSnapshot;
import org.springframework.stereotype.Component;

/**
 * Feishu 文档读取端口的本地 mock 实现。
 *
 * <p>P0 本地联调默认使用确定性内容，真实 OpenAPI 适配器后续可在同一端口替换。
 */
@Component
public final class MockFeishuDocumentClient implements FeishuDocumentClient {

    @Override
    public FeishuDocumentSnapshot fetch(String source) {
        return new FeishuDocumentSnapshot(
                extractFeishuToken(source),
                source == null ? "" : source,
                "feishu-" + extractFeishuToken(source),
                "mock-revision-1",
                "# Feishu Mock Document\n\n来源：" + (source == null ? "" : source) + "\n\nRD-Bot P0 知识库生产化导入验证内容。",
                System.currentTimeMillis()
        );
    }

    private String extractFeishuToken(String source) {
        String trimmed = source == null ? "" : source.strip();
        if (trimmed.isBlank()) {
            return "empty";
        }
        String[] parts = trimmed.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("docx".equalsIgnoreCase(parts[i]) || "wiki".equalsIgnoreCase(parts[i])) {
                String token = parts[i + 1];
                int queryIndex = token.indexOf('?');
                return queryIndex > 0 ? token.substring(0, queryIndex) : token;
            }
        }
        return trimmed.replaceAll("[^A-Za-z0-9_-]", "");
    }
}
