CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS knowledge_bases (
    id          BIGINT PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_bases_name ON knowledge_bases (name);

CREATE TABLE IF NOT EXISTS knowledge_documents (
    id                 BIGINT PRIMARY KEY,
    knowledge_base_id  BIGINT NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    source_name        VARCHAR(256) NOT NULL,
    knowledge_type     VARCHAR(64) NOT NULL,
    mime_type          VARCHAR(128) NOT NULL DEFAULT 'text/plain',
    status             VARCHAR(32) NOT NULL,
    enabled            BOOLEAN NOT NULL DEFAULT TRUE,
    chunk_count        INTEGER NOT NULL DEFAULT 0,
    source_type        VARCHAR(32) NOT NULL DEFAULT 'LOCAL',
    source_token       VARCHAR(512) NOT NULL DEFAULT '',
    source_url         TEXT NOT NULL DEFAULT '',
    revision_id        VARCHAR(128) NOT NULL DEFAULT '',
    checksum           VARCHAR(128) NOT NULL DEFAULT '',
    raw_preview        TEXT NOT NULL DEFAULT '',
    raw_content        TEXT,
    node_logs_json     JSONB NOT NULL DEFAULT '[]'::jsonb,
    last_synced_at     TIMESTAMPTZ,
    next_refresh_at    TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_documents_kb ON knowledge_documents (knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_documents_source
    ON knowledge_documents (knowledge_base_id, source_type, source_token);
CREATE INDEX IF NOT EXISTS idx_knowledge_documents_refresh ON knowledge_documents (next_refresh_at);

CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id                BIGINT PRIMARY KEY,
    document_id       BIGINT NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    knowledge_base_id BIGINT NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    chunk_index       INTEGER NOT NULL,
    content           TEXT NOT NULL,
    content_hash      VARCHAR(64) NOT NULL DEFAULT '',
    knowledge_type    VARCHAR(64) NOT NULL,
    source_name       VARCHAR(256) NOT NULL,
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    metadata_json     JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_document ON knowledge_chunks (document_id, chunk_index);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_kb ON knowledge_chunks (knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_metadata ON knowledge_chunks USING gin(metadata_json);

CREATE TABLE IF NOT EXISTS knowledge_vectors (
    id            BIGINT PRIMARY KEY,
    content       TEXT NOT NULL,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    embedding     vector(1536) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_vectors_metadata ON knowledge_vectors USING gin(metadata_json);
CREATE INDEX IF NOT EXISTS idx_knowledge_vectors_embedding
    ON knowledge_vectors USING hnsw (embedding vector_cosine_ops);

CREATE TABLE IF NOT EXISTS ingestion_tasks (
    id                BIGINT PRIMARY KEY,
    pipeline_id       VARCHAR(128) NOT NULL,
    knowledge_base_id BIGINT NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    document_id       BIGINT REFERENCES knowledge_documents(id) ON DELETE SET NULL,
    source_type       VARCHAR(64) NOT NULL,
    source_location   TEXT NOT NULL DEFAULT '',
    source_file_name  VARCHAR(512) NOT NULL DEFAULT '',
    status            VARCHAR(32) NOT NULL,
    chunk_count       INTEGER NOT NULL DEFAULT 0,
    error_message     TEXT NOT NULL DEFAULT '',
    metadata_json     JSONB NOT NULL DEFAULT '{}'::jsonb,
    started_at        TIMESTAMPTZ NOT NULL,
    completed_at      TIMESTAMPTZ,
    created_by        VARCHAR(128) NOT NULL DEFAULT 'local',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ingestion_tasks_kb ON ingestion_tasks (knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_ingestion_tasks_status ON ingestion_tasks (status, created_at);

CREATE TABLE IF NOT EXISTS ingestion_task_nodes (
    id            BIGINT PRIMARY KEY,
    task_id       BIGINT NOT NULL REFERENCES ingestion_tasks(id) ON DELETE CASCADE,
    pipeline_id   VARCHAR(128) NOT NULL,
    node_id       VARCHAR(128) NOT NULL,
    node_type     VARCHAR(64) NOT NULL,
    node_order    INTEGER NOT NULL,
    status        VARCHAR(32) NOT NULL,
    duration_ms   BIGINT NOT NULL DEFAULT 0,
    message       TEXT NOT NULL DEFAULT '',
    error_message TEXT NOT NULL DEFAULT '',
    output_json   JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ingestion_task_nodes_task ON ingestion_task_nodes (task_id, node_order);

CREATE TABLE IF NOT EXISTS repair_records (
    id             BIGINT PRIMARY KEY,
    ticket_id      VARCHAR(256) NOT NULL,
    ticket_url     TEXT NOT NULL DEFAULT '',
    title          TEXT NOT NULL DEFAULT '',
    status         VARCHAR(32) NOT NULL,
    rag_summary    TEXT NOT NULL DEFAULT '',
    executor_json  JSONB NOT NULL DEFAULT '{}'::jsonb,
    docker_json    JSONB NOT NULL DEFAULT '{}'::jsonb,
    github_json    JSONB NOT NULL DEFAULT '{}'::jsonb,
    test_json      JSONB NOT NULL DEFAULT '{}'::jsonb,
    risk_json      JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message  TEXT NOT NULL DEFAULT '',
    extension_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_repair_records_ticket ON repair_records (ticket_id);
CREATE INDEX IF NOT EXISTS idx_repair_records_status ON repair_records (status, updated_at);

CREATE TABLE IF NOT EXISTS repair_record_artifacts (
    id               BIGINT PRIMARY KEY,
    repair_record_id BIGINT NOT NULL REFERENCES repair_records(id) ON DELETE CASCADE,
    artifact_type    VARCHAR(64) NOT NULL,
    artifact_uri     TEXT NOT NULL,
    summary          TEXT NOT NULL DEFAULT '',
    content_hash     VARCHAR(128) NOT NULL DEFAULT '',
    extension_json   JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_repair_record_artifacts_record ON repair_record_artifacts (repair_record_id);
