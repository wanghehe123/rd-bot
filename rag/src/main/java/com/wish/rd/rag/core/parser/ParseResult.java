package com.wish.rd.rag.core.parser;

import java.util.Map;

public record ParseResult(String text, Map<String, Object> metadata) {

    public ParseResult {
        text = text == null ? "" : text;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ParseResult ofText(String text) {
        return new ParseResult(text, Map.of());
    }

    public static ParseResult of(String text, Map<String, Object> metadata) {
        return new ParseResult(text, metadata);
    }
}
