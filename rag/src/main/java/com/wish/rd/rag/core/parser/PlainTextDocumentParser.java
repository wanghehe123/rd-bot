package com.wish.rd.rag.core.parser;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 纯文本解析器：把字节按 UTF-8 解码并做基础清理（去多余空白等）。
 *
 * <p>作为 {@link DocumentParserSelector} 的兜底解析器，支持 text/* 系列 MIME 类型
 * 以及空/未知类型。
 */
public final class PlainTextDocumentParser implements DocumentParser {

    @Override
    public ParserType parserType() {
        return ParserType.TEXT;
    }

    @Override
    public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParseResult.ofText("");
        }
        // 解码后调用清理工具做规范化（去多余空白、统一换行等）
        return ParseResult.of(TextCleanupUtil.normalize(new String(content, StandardCharsets.UTF_8)),
                Map.of("parser", parserType().name()));
    }

    /** 支持 text/* 系列、空或未知类型。 */
    @Override
    public boolean supports(String mimeType) {
        return mimeType == null || mimeType.isBlank() || mimeType.startsWith("text/");
    }
}
