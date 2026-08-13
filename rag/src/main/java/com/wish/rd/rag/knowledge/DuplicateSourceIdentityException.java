package com.wish.rd.rag.knowledge;

/**
 * 同一知识库内该来源身份已经有活动文档。调用方应走更新路径，而不是新建第二份。
 *
 * <p>与 WP-6 的 {@code uk_knowledge_documents_active_identity} 唯一索引同一语义，
 * 但先在领域层抛出，避免把数据库约束冲突泄露成未处理的服务端错误。
 */
public final class DuplicateSourceIdentityException extends IllegalStateException {

    public DuplicateSourceIdentityException(String message) {
        super(message);
    }
}
