package com.wish.rd.engine.requirement.audit;

/** Host-owned evidence source that may promote a record to {@code COMPLETED}. */
public enum EvidenceSourceKind {
    HOST_VERIFICATION,
    QA_EVIDENCE,
    HOST_ASSERTION,
    PUBLICATION_LEDGER,
    WORKSPACE_FINGERPRINT
}
