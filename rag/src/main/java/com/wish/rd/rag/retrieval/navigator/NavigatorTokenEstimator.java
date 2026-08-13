package com.wish.rd.rag.retrieval.navigator;

/**
 * 导航上下文 Token 估算。仓库里唯一现成公式在
 * {@code bootstrap/.../KnowledgeMapController} 的私有 {@code estimateTokens}：
 * {@code (length + 3) / 4}。那是 bootstrap 私有方法，rag 不能依赖；此处复用同一约定，
 * 按 UTF-16 代码单元而不是模型 tokenizer 计。
 */
public final class NavigatorTokenEstimator {

    private NavigatorTokenEstimator() {
    }

    /**
     * @param text 将进入上下文的文本，通常是已围栏的证据
     * @return 非负估算值
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (text.length() + 3) / 4;
    }
}
