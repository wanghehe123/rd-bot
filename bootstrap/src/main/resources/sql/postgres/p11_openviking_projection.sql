-- WP-1: stable document identity, immutable revisions, and soft-delete tombs.
-- Do not add the active-only unique index on source_identity_key here; WP-6
-- must audit duplicate survivors first.

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
