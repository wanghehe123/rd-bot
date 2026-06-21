package com.wish.rd.rag.core.parser;

public final class TextCleanupUtil {

    private TextCleanupUtil() {
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }
}
