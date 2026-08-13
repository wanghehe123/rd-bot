package com.wish.rd.rag.knowledge.projection;

/**
 * 管理面只读远端调用失败，且不是"投影关闭"。
 */
public final class ProjectionRemoteUnavailableException extends RuntimeException {

    public ProjectionRemoteUnavailableException(String message) {
        super(message == null ? "" : message);
    }
}
