package com.wish.rd.engine.requirement.audit;

/** Lifecycle of an audited record. */
public enum AuditedRecordStatus {
    PENDING,
    COMPLETED,
    BLOCKED,
    UNTRUSTED
}
