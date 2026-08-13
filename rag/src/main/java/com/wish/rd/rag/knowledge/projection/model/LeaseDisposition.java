package com.wish.rd.rag.knowledge.projection.model;

/**
 * settle 时对租约的处置。必须显式声明，不得由目标状态是否终态推断：
 * 等待远端、退避重试和人工挂起都要求把行交还给其他实例。
 */
public enum LeaseDisposition {

    /** 保留当前租约，调用方还要在同一轮里继续操作这一行。 */
    KEEP,

    /** 立即释放租约，任何实例都可以在到期后接手。 */
    RELEASE
}
