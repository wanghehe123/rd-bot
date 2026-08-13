package com.wish.rd.rag.knowledge.projection;

/**
 * 管理面并发冲突：CAS 失败或投影关闭时的核验/对账。
 */
public final class ProjectionAdminConflictException extends IllegalStateException {

    public ProjectionAdminConflictException(String message) {
        super(message == null ? "" : message);
    }
}
