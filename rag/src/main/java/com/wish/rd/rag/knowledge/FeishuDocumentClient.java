package com.wish.rd.rag.knowledge;

/**
 * Feishu 文档读取端口。
 *
 * <p>P0 只定义可测试外壳。真实 Feishu OpenAPI 鉴权、字段和错误码由后续适配层实现，
 * RAG 核心只依赖规范化后的文档快照。
 */
@FunctionalInterface
public interface FeishuDocumentClient {

    /**
     * 读取 Feishu docx/wiki URL 或 token。
     *
     * @param source docx/wiki URL 或 token
     * @return 文档快照
     */
    FeishuDocumentSnapshot fetch(String source);
}
