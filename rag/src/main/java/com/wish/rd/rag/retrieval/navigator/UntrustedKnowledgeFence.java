package com.wish.rd.rag.retrieval.navigator;

/**
 * 把远端 L0/L1/L2 正文标成不可信数据区。先转义再封口，正文里的
 * {@code </untrusted_knowledge>} 或指令口吻都不能拆掉围栏，也不能被当成系统指令。
 */
public final class UntrustedKnowledgeFence {

    public static final String OPEN_TAG = "<untrusted_knowledge>";
    public static final String CLOSE_TAG = "</untrusted_knowledge>";

    private UntrustedKnowledgeFence() {
    }

    /**
     * 转义后包进数据区。空输入也保持成对标签，避免调用方拼接时露出缺口。
     *
     * @param raw 远端原文
     * @return 带围栏的文本
     */
    public static String wrap(String raw) {
        return OPEN_TAG + "\n" + escape(raw) + "\n" + CLOSE_TAG;
    }

    /**
     * 把尖括号与 {@code &} 变成实体，使正文无法提前关闭围栏或注入标签。
     *
     * @param raw 远端原文
     * @return 转义后的文本
     */
    public static String escape(String raw) {
        String text = raw == null ? "" : raw;
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 文本是否是「开头开标签、结尾唯一闭标签」的围栏块。
     *
     * @param text 待检查文本
     * @return 完整围栏时为 true
     */
    public static boolean isFullyFenced(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String stripped = text.strip();
        if (!stripped.startsWith(OPEN_TAG) || !stripped.endsWith(CLOSE_TAG)) {
            return false;
        }
        int closeAt = stripped.indexOf(CLOSE_TAG);
        return closeAt == stripped.length() - CLOSE_TAG.length();
    }

    /**
     * 取出围栏内转义后的正文；不是围栏块时返回空串，避免把指令区误当数据。
     *
     * @param fenced 围栏文本
     * @return 内层转义文本
     */
    public static String innerEscaped(String fenced) {
        if (!isFullyFenced(fenced)) {
            return "";
        }
        String stripped = fenced.strip();
        int start = OPEN_TAG.length();
        int end = stripped.length() - CLOSE_TAG.length();
        String inner = stripped.substring(start, end);
        if (inner.startsWith("\n")) {
            inner = inner.substring(1);
        }
        if (inner.endsWith("\n")) {
            inner = inner.substring(0, inner.length() - 1);
        }
        return inner;
    }
}
