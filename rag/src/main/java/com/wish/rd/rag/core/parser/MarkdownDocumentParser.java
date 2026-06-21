package com.wish.rd.rag.core.parser;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Markdown 文档解析器。
 *
 * <p>MVP 阶段不做真正的 Markdown→HTML 转换，仅把字节按 UTF-8 解码为纯文本，
 * 并在元数据中标记所用解析器类型，便于上游追溯。空内容返回空文本结果。
 */
public final class MarkdownDocumentParser implements DocumentParser {

    @Override
    public ParserType parserType() {
        return ParserType.MARKDOWN;
    }

    @Override
    public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParseResult.ofText("");
        }
        return ParseResult.of(new String(content, StandardCharsets.UTF_8), Map.of("parser", parserType().name()));
    }

    /** 支持的 MIME 类型：text/markdown、text/x-markdown、application/markdown。 */
    @Override
    public boolean supports(String mimeType) {
        return mimeType != null && (
                mimeType.equalsIgnoreCase("text/markdown")
                        || mimeType.equalsIgnoreCase("text/x-markdown")
                        || mimeType.equalsIgnoreCase("application/markdown")
        );
    }
}
