package com.wish.rd.rag.knowledge.projection.model;

/**
 * 重复身份收敛结果。行版本不匹配时整体不提交。
 */
public enum InventorySupersedeStatus {
    APPLIED,
    CONFLICT
}
