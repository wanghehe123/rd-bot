-- OpenViking projection schema: identity/revision (WP-1) plus binding/outbox (WP-2).
-- Do not add the active-only unique index on source_identity_key here; WP-6
-- must audit duplicate survivors first.
-- Bindings use ON DELETE RESTRICT so tombs and remote URIs survive local deletes.
-- Outbox rows are immutable audit identifiers and must not FK-cascade with documents.

ALTER TABLE knowledge_bases
    ADD COLUMN IF NOT EXISTS lifecycle_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE knowledge_bases
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
ALTER TABLE knowledge_bases
    ADD COLUMN IF NOT EXISTS purge_after TIMESTAMPTZ;
ALTER TABLE knowledge_bases
    ADD COLUMN IF NOT EXISTS sync_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE knowledge_bases
    ADD COLUMN IF NOT EXISTS row_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE knowledge_documents
    ADD COLUMN IF NOT EXISTS sync_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE knowledge_documents
    ADD COLUMN IF NOT EXISTS current_revision_id BIGINT;
ALTER TABLE knowledge_documents
    ADD COLUMN IF NOT EXISTS source_identity_key VARCHAR(128);
ALTER TABLE knowledge_documents
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
ALTER TABLE knowledge_documents
    ADD COLUMN IF NOT EXISTS purge_after TIMESTAMPTZ;
ALTER TABLE knowledge_documents
    ADD COLUMN IF NOT EXISTS superseded_by_document_id BIGINT;
ALTER TABLE knowledge_documents
    ADD COLUMN IF NOT EXISTS row_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE knowledge_documents
    ADD COLUMN IF NOT EXISTS local_only_override BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_knowledge_documents_identity
    ON knowledge_documents (knowledge_base_id, source_identity_key)
    WHERE source_identity_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_knowledge_documents_deleted
    ON knowledge_documents (deleted_at)
    WHERE deleted_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS knowledge_document_revisions (
    id                    BIGINT PRIMARY KEY,
    document_id           BIGINT NOT NULL REFERENCES knowledge_documents(id) ON DELETE RESTRICT,
    sync_version          BIGINT NOT NULL,
    source_revision_id    VARCHAR(128) NOT NULL DEFAULT '',
    checksum              VARCHAR(128) NOT NULL,
    canonical_mime_type   VARCHAR(128) NOT NULL DEFAULT 'text/markdown',
    canonical_content     TEXT NOT NULL DEFAULT '',
    parser_name           VARCHAR(64) NOT NULL DEFAULT 'rd-bot-default',
    parser_version        VARCHAR(32) NOT NULL DEFAULT '1',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, sync_version),
    UNIQUE (document_id, checksum)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_document_revisions_document
    ON knowledge_document_revisions (document_id, sync_version);

CREATE TABLE IF NOT EXISTS knowledge_external_index_bindings (
    provider                       VARCHAR(32) NOT NULL,
    document_id                    BIGINT NOT NULL,
    knowledge_base_id              BIGINT NOT NULL,
    remote_uri                     TEXT NOT NULL,
    ownership_marker               VARCHAR(256) NOT NULL,
    desired_state                  VARCHAR(32) NOT NULL,
    desired_version                BIGINT NOT NULL,
    desired_checksum               VARCHAR(128) NOT NULL DEFAULT '',
    observed_state                 VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    observed_version               BIGINT,
    observed_checksum              VARCHAR(128) NOT NULL DEFAULT '',
    projection_status              VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    active_operation_id            BIGINT,
    remote_task_id                 VARCHAR(256) NOT NULL DEFAULT '',
    semantic_config_fingerprint    VARCHAR(128) NOT NULL DEFAULT '',
    last_submitted_at              TIMESTAMPTZ,
    last_verified_at               TIMESTAMPTZ,
    last_error_code                VARCHAR(64) NOT NULL DEFAULT '',
    last_error_message             TEXT NOT NULL DEFAULT '',
    row_version                    BIGINT NOT NULL DEFAULT 0,
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, document_id),
    UNIQUE (provider, remote_uri),
    FOREIGN KEY (document_id) REFERENCES knowledge_documents(id) ON DELETE RESTRICT,
    FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS knowledge_external_index_outbox (
    event_id               BIGINT PRIMARY KEY,
    idempotency_key        VARCHAR(128) NOT NULL,
    provider               VARCHAR(32) NOT NULL,
    operation_type         VARCHAR(32) NOT NULL,
    knowledge_base_id      BIGINT NOT NULL,
    document_id            BIGINT,
    sync_version           BIGINT NOT NULL,
    checksum               VARCHAR(128) NOT NULL DEFAULT '',
    remote_uri             TEXT NOT NULL DEFAULT '',
    revision_id            BIGINT,
    payload_ref            TEXT NOT NULL DEFAULT '',
    status                 VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    remote_task_id         VARCHAR(256) NOT NULL DEFAULT '',
    remote_operation_id    VARCHAR(256) NOT NULL DEFAULT '',
    lease_owner            VARCHAR(128) NOT NULL DEFAULT '',
    lease_until            TIMESTAMPTZ,
    attempt_count          INTEGER NOT NULL DEFAULT 0,
    max_attempts           INTEGER NOT NULL DEFAULT 8,
    next_visible_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at           TIMESTAMPTZ,
    last_error_code        VARCHAR(64) NOT NULL DEFAULT '',
    last_error_message     TEXT NOT NULL DEFAULT '',
    row_version            BIGINT NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (idempotency_key),
    UNIQUE (provider, document_id, sync_version, operation_type)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_external_index_outbox_kb_op
    ON knowledge_external_index_outbox (provider, knowledge_base_id, sync_version, operation_type)
    WHERE document_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_knowledge_external_index_outbox_claim
    ON knowledge_external_index_outbox (status, next_visible_at, created_at);
