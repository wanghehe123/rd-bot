package com.wish.rd.engine.requirement.audit;

/** Whether every blocking record was evidenced in this audit run. */
public enum AuditCompletion {
    COMPLETE,
    INCOMPLETE,
    BLOCKED
}
