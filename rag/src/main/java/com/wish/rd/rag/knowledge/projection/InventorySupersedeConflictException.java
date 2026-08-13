package com.wish.rd.rag.knowledge.projection;

/**
 * 重复身份收敛的 CAS 冲突。调用方必须整体放弃，不得部分取代。
 */
public final class InventorySupersedeConflictException extends IllegalStateException {

    public InventorySupersedeConflictException(String message) {
        super(message == null ? "" : message);
    }
}
