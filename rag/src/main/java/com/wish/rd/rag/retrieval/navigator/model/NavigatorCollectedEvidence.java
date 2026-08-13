package com.wish.rd.rag.retrieval.navigator.model;

import java.util.Objects;

/**
 * 导航过程中收集到的一条已准入证据。正文已经过不可信围栏，调用方不得再把它当指令解析。
 *
 * @param admitted    allowlist 通过时的命中、绑定与文档快照
 * @param fencedText  用 {@code <untrusted_knowledge>} 包起来的文本
 * @param tier        当前最深层级，{@code L0}/{@code L1}/{@code L2}
 * @param resourceUri 实际读取的远端 URI，只来自绑定或命中，不来自正文
 */
public record NavigatorCollectedEvidence(
        AdmittedKnowledgeEvidence admitted,
        String fencedText,
        String tier,
        String resourceUri
) {

    public NavigatorCollectedEvidence {
        Objects.requireNonNull(admitted, "admitted must not be null");
        fencedText = fencedText == null ? "" : fencedText;
        tier = tier == null || tier.isBlank() ? "L0" : tier.strip().toUpperCase();
        resourceUri = resourceUri == null ? "" : resourceUri;
    }
}
