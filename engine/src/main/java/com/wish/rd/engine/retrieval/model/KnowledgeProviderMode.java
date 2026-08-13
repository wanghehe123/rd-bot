package com.wish.rd.engine.retrieval.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 需求交付读路径的知识提供模式。只影响检索，不得改变投影写路径。
 */
public enum KnowledgeProviderMode {

    /** 只走本地 VectorStore，对导航端口零调用。 */
    LOCAL,

    /** 本地结果原样返回；远端只做旁路探测，失败与超时不得冒泡。 */
    SHADOW,

    /** 以远端三层导航为主；不可用或零准入时必须显式降级，禁止静默装成本地结果。 */
    OPENVIKING;

    /**
     * 解析配置值。空白视为未配置，回退 {@link #LOCAL}；非法值必须失败并列出合法取值。
     *
     * @param raw 配置原文，可为 null
     * @return 模式
     * @throws IllegalArgumentException 取值不在合法集合内
     */
    public static KnowledgeProviderMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return LOCAL;
        }
        String normalized = raw.strip().toUpperCase(Locale.ROOT);
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "rd.rag.knowledge-provider-mode must be one of "
                            + Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "))
                            + "; got: " + raw.strip(),
                    ex);
        }
    }
}
