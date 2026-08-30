-- P19 project-scoped Agent Memory. PostgreSQL is the canonical store; no external projection is enabled here.

CREATE TABLE IF NOT EXISTS rd_project_memories (
    id                BIGINT PRIMARY KEY,
    project_id        BIGINT NOT NULL REFERENCES rd_projects(id) ON DELETE RESTRICT,
    scope_role        VARCHAR(64) NOT NULL DEFAULT '',
    memory_type       VARCHAR(32) NOT NULL CHECK (memory_type IN ('SEMANTIC', 'EPISODIC', 'PROCEDURAL')),
    logical_key       VARCHAR(256) NOT NULL,
    head_revision_id  BIGINT,
    head_version      BIGINT NOT NULL DEFAULT 0 CHECK (head_version >= 0),
    row_version       BIGINT NOT NULL DEFAULT 1 CHECK (row_version > 0),
    lifecycle_status  VARCHAR(16) NOT NULL DEFAULT 'ENABLED' CHECK (lifecycle_status IN ('ENABLED', 'DELETED')),
    deleted_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, scope_role, memory_type, logical_key),
    UNIQUE (id, head_revision_id)
);

CREATE TABLE IF NOT EXISTS rd_project_memory_revisions (
    id                       BIGINT PRIMARY KEY,
    memory_id                BIGINT NOT NULL REFERENCES rd_project_memories(id) ON DELETE RESTRICT,
    version                  BIGINT NOT NULL CHECK (version > 0),
    status                   VARCHAR(16) NOT NULL CHECK (status IN ('CANDIDATE', 'ACTIVE', 'SUPERSEDED', 'EXPIRED', 'REJECTED', 'QUARANTINED', 'DELETED')),
    title                    TEXT NOT NULL DEFAULT '',
    summary                  TEXT NOT NULL DEFAULT '',
    content_json             JSONB NOT NULL DEFAULT '{}'::jsonb,
    content_hash             VARCHAR(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    schema_version           VARCHAR(64) NOT NULL,
    supersedes_revision_id   BIGINT,
    created_by_operation_id  BIGINT,
    confidence               NUMERIC(5,4) NOT NULL DEFAULT 0 CHECK (confidence >= 0 AND confidence <= 1),
    importance               NUMERIC(5,4) NOT NULL DEFAULT 0 CHECK (importance >= 0 AND importance <= 1),
    evidence_quality         NUMERIC(5,4) NOT NULL DEFAULT 0 CHECK (evidence_quality >= 0 AND evidence_quality <= 1),
    redacted                 BOOLEAN NOT NULL DEFAULT FALSE,
    valid_from               TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_to                 TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_version              BIGINT NOT NULL DEFAULT 1 CHECK (row_version > 0),
    UNIQUE (memory_id, version),
    UNIQUE (memory_id, id),
    FOREIGN KEY (memory_id, supersedes_revision_id)
        REFERENCES rd_project_memory_revisions(memory_id, id) ON DELETE RESTRICT
        DEFERRABLE INITIALLY DEFERRED
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_rd_project_memories_same_memory_head') THEN
        ALTER TABLE rd_project_memories
            ADD CONSTRAINT fk_rd_project_memories_same_memory_head
            FOREIGN KEY (id, head_revision_id)
            REFERENCES rd_project_memory_revisions(memory_id, id) ON DELETE RESTRICT
            DEFERRABLE INITIALLY DEFERRED;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS rd_project_memory_sources (
    id                   BIGINT PRIMARY KEY,
    revision_id          BIGINT NOT NULL REFERENCES rd_project_memory_revisions(id) ON DELETE RESTRICT,
    project_id           BIGINT NOT NULL REFERENCES rd_projects(id) ON DELETE RESTRICT,
    task_id              BIGINT REFERENCES rd_tasks(id) ON DELETE SET NULL,
    stage_run_id         BIGINT REFERENCES rd_agent_stage_runs(id) ON DELETE SET NULL,
    artifact_id          BIGINT REFERENCES rd_agent_stage_artifacts(id) ON DELETE SET NULL,
    source_uri           TEXT NOT NULL,
    source_content_hash  VARCHAR(64) NOT NULL CHECK (source_content_hash ~ '^[0-9a-f]{64}$'),
    repository_revision  VARCHAR(256) NOT NULL DEFAULT '',
    extractor_version    VARCHAR(64) NOT NULL,
    schema_version       VARCHAR(64) NOT NULL,
    redacted_summary     TEXT NOT NULL DEFAULT '',
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (revision_id, source_uri, source_content_hash)
);

CREATE TABLE IF NOT EXISTS rd_project_memory_operations (
    id                   BIGINT PRIMARY KEY,
    operation_key        VARCHAR(64) NOT NULL CHECK (operation_key ~ '^[0-9a-f]{64}$'),
    project_id           BIGINT NOT NULL REFERENCES rd_projects(id) ON DELETE RESTRICT,
    operation_kind       VARCHAR(64) NOT NULL,
    source_kind          VARCHAR(64) NOT NULL,
    source_identity      VARCHAR(256) NOT NULL,
    source_content_hash  VARCHAR(64) NOT NULL CHECK (source_content_hash ~ '^[0-9a-f]{64}$'),
    extractor_version    VARCHAR(64) NOT NULL,
    schema_version       VARCHAR(64) NOT NULL,
    status               VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_no           INTEGER NOT NULL DEFAULT 0 CHECK (attempt_no >= 0),
    max_attempts         INTEGER NOT NULL DEFAULT 3 CHECK (max_attempts > 0),
    lease_owner          VARCHAR(128) NOT NULL DEFAULT '',
    lease_until          TIMESTAMPTZ,
    fencing_token        BIGINT NOT NULL DEFAULT 0 CHECK (fencing_token >= 0),
    row_version          BIGINT NOT NULL DEFAULT 1 CHECK (row_version > 0),
    next_visible_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    checkpoint_json      JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_error           TEXT NOT NULL DEFAULT '',
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (operation_key)
);

CREATE TABLE IF NOT EXISTS rd_project_memory_legacy_links (
    legacy_experience_id VARCHAR(256) PRIMARY KEY,
    project_id           BIGINT NOT NULL REFERENCES rd_projects(id) ON DELETE RESTRICT,
    revision_id          BIGINT REFERENCES rd_project_memory_revisions(id) ON DELETE SET NULL,
    decision             VARCHAR(32) NOT NULL,
    reason               TEXT NOT NULL DEFAULT '',
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rd_project_memory_search
    ON rd_project_memories (project_id, scope_role, memory_type, lifecycle_status)
    WHERE lifecycle_status = 'ENABLED';
CREATE INDEX IF NOT EXISTS idx_rd_project_memory_revisions_retrieval
    ON rd_project_memory_revisions (memory_id, status, valid_from, valid_to)
    WHERE status = 'ACTIVE' AND redacted = TRUE;
CREATE INDEX IF NOT EXISTS idx_rd_project_memory_sources_revision
    ON rd_project_memory_sources (revision_id, source_content_hash);
CREATE INDEX IF NOT EXISTS idx_rd_project_memory_operations_claim
    ON rd_project_memory_operations (status, next_visible_at, lease_until, id);

CREATE TABLE IF NOT EXISTS rd_project_memory_modes (
    project_id    BIGINT PRIMARY KEY REFERENCES rd_projects(id) ON DELETE RESTRICT,
    capture_mode  VARCHAR(16) NOT NULL DEFAULT 'OFF'
        CHECK (capture_mode IN ('OFF', 'SHADOW', 'ACTIVE')),
    read_mode     VARCHAR(16) NOT NULL DEFAULT 'LEGACY'
        CHECK (read_mode IN ('LEGACY', 'SHADOW', 'DUAL', 'PRIMARY')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
