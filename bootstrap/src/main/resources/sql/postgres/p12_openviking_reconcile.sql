-- OpenViking reconcile findings ledger (WP-4).
-- Orphans and foreign-owned resources are recorded here; this table is not a delete permit.

CREATE TABLE IF NOT EXISTS knowledge_reconcile_findings (
    id             BIGINT PRIMARY KEY,
    provider       VARCHAR(32) NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    finding_type   VARCHAR(40) NOT NULL,   -- MISSING_REMOTE/STALE_REMOTE/ORPHAN_REMOTE/FOREIGN_OWNER/DRIFTED_MARKER/LEASE_EXPIRED
    remote_uri     TEXT NOT NULL DEFAULT '',
    document_id    BIGINT,
    detail         TEXT NOT NULL DEFAULT '',
    status         VARCHAR(32) NOT NULL DEFAULT 'OPEN', -- OPEN/AUTO_REPAIRED/QUARANTINED/RESOLVED
    first_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider, knowledge_base_id, finding_type, remote_uri, document_id)
);
