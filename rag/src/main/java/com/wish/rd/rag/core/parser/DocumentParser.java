package com.wish.rd.rag.core.parser;

import java.util.Map;

/**
 * 文档解析器接口：把原始字节解析为纯文本，供后续分块使用。
 *
 * <p>由 {@link DocumentParserSelector} 按 MIME 类型选择具体实现。
 */
public interface DocumentParser {

    /** 解析器类型（Markdown/文本等），用于回退选择。 */
    ParserType parserType();

    /**
     * 把字节内容解析为文本结果。
     *
     * @param content  原始字节
     * @param mimeType 文档 MIME 类型
     * @param options  额外解析选项（当前未使用）
     * @return 解析结果（含文本与元数据）
     */
    ParseResult parse(byte[] content, String mimeType, Map<String, Object> options);

    /** 是否支持指定 MIME 类型，默认返回 true（作为兜底）。 */
    default boolean supports(String mimeType) {
        return true;
    }
}
