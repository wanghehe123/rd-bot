package com.wish.rd.rag.knowledge;

/**
 * Feishu 文档快照：导入器只关心规范化内容，不关心开放平台响应格式。
 *
 * @param sourceToken              文档 token
 * @param sourceUrl                文档 URL
 * @param title                    文档标题
 * @param revisionId               文档版本
 * @param content                  文档正文
 * @param fetchedAtEpochMillis     抓取时间
 */
public record FeishuDocumentSnapshot(
        String sourceToken,
        String sourceUrl,
        String title,
        String revisionId,
        String content,
        long fetchedAtEpochMillis
) {

    public FeishuDocumentSnapshot {
        sourceToken = sourceToken == null ? "" : sourceToken.strip();
        sourceUrl = sourceUrl == null ? "" : sourceUrl.strip();
        title = title == null || title.isBlank() ? "feishu-document" : title.strip();
        revisionId = revisionId == null ? "" : revisionId.strip();
        content = content == null ? "" : content;
        fetchedAtEpochMillis = Math.max(0L, fetchedAtEpochMillis);
    }
}
