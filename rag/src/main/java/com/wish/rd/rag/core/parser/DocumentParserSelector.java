package com.wish.rd.rag.core.parser;

import java.util.List;
import com.wish.rd.rag.core.parser.model.ParserType;

/**
 * 文档解析器选择器：按 MIME 类型在已注册的解析器中挑选合适的实现。
 *
 * <p>选择策略：优先返回 {@code supports(mimeType)} 为 true 的解析器；
 * 若无精确匹配，回退到第一个文本解析器（{@link ParserType#TEXT}）；
 * 连文本解析器都没有则抛异常。
 */
public final class DocumentParserSelector {

    private final List<DocumentParser> parsers;

    public DocumentParserSelector(List<DocumentParser> parsers) {
        this.parsers = parsers == null ? List.of() : List.copyOf(parsers);
    }

    /**
     * 为指定 MIME 类型选择解析器。
     *
     * @param mimeType 文档 MIME 类型，如 text/markdown、text/plain
     * @return 匹配的解析器；无精确匹配时回退到文本解析器
     */
    public DocumentParser select(String mimeType) {
        return parsers.stream()
                .filter(parser -> parser.supports(mimeType))
                .findFirst()
                // 无精确匹配时回退到通用文本解析器
                .orElseGet(() -> parsers.stream()
                        .filter(parser -> parser.parserType() == ParserType.TEXT)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No text parser configured")));
    }
}
