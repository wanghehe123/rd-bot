CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS admin_users (
    id            BIGINT PRIMARY KEY,
    username      VARCHAR(128) NOT NULL,
    role          VARCHAR(32) NOT NULL DEFAULT 'user',
    avatar        TEXT NOT NULL DEFAULT '',
    password_hash VARCHAR(128) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_admin_users_username ON admin_users (username);

INSERT INTO admin_users (id, username, role, avatar, password_hash)
VALUES (1, 'admin', 'admin', '', '8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918')
ON CONFLICT (username) DO NOTHING;

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

-- 管理台意图树：生产可见配置必须落 PostgreSQL，禁止只存在 JVM 内存。
CREATE TABLE IF NOT EXISTS t_intent_node (
    id                    VARCHAR(64) PRIMARY KEY,
    kb_id                 VARCHAR(128) NOT NULL DEFAULT '',
    intent_code           VARCHAR(128) NOT NULL,
    name                  VARCHAR(128) NOT NULL,
    level                 SMALLINT NOT NULL DEFAULT 2,
    parent_code           VARCHAR(128) NOT NULL DEFAULT '',
    description           VARCHAR(512) NOT NULL DEFAULT '',
    examples              TEXT NOT NULL DEFAULT '[]',
    collection_name       VARCHAR(128) NOT NULL DEFAULT '',
    top_k                 INTEGER NOT NULL DEFAULT 5,
    mcp_tool_id           VARCHAR(128) NOT NULL DEFAULT '',
    kind                  SMALLINT NOT NULL DEFAULT 0,
    prompt_snippet        TEXT NOT NULL DEFAULT '',
    prompt_template       TEXT NOT NULL DEFAULT '',
    param_prompt_template TEXT NOT NULL DEFAULT '',
    sort_order            INTEGER NOT NULL DEFAULT 0,
    enabled               SMALLINT NOT NULL DEFAULT 1,
    create_by             VARCHAR(128) NOT NULL DEFAULT 'rd-bot',
    update_by             VARCHAR(128) NOT NULL DEFAULT 'rd-bot',
    create_time           TIMESTAMP NOT NULL DEFAULT now(),
    update_time           TIMESTAMP NOT NULL DEFAULT now(),
    deleted               SMALLINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_t_intent_node_code_active
    ON t_intent_node (intent_code) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_intent_node_parent
    ON t_intent_node (parent_code, sort_order) WHERE deleted = 0;

-- 管理台摄取管道：管道定义和节点拓扑必须落 PostgreSQL，任务执行记录使用 ingestion_tasks。
CREATE TABLE IF NOT EXISTS t_ingestion_pipeline (
    id          VARCHAR(64) PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    created_by  VARCHAR(128) NOT NULL DEFAULT 'rd-bot',
    updated_by  VARCHAR(128) NOT NULL DEFAULT 'rd-bot',
    create_time TIMESTAMP NOT NULL DEFAULT now(),
    update_time TIMESTAMP NOT NULL DEFAULT now(),
    deleted     SMALLINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_t_ingestion_pipeline_active
    ON t_ingestion_pipeline (deleted, update_time);

ALTER TABLE t_ingestion_pipeline
    ALTER COLUMN id TYPE VARCHAR(64),
    ALTER COLUMN name TYPE VARCHAR(128),
    ALTER COLUMN created_by TYPE VARCHAR(128),
    ALTER COLUMN updated_by TYPE VARCHAR(128);

CREATE TABLE IF NOT EXISTS t_ingestion_pipeline_node (
    id             VARCHAR(64) PRIMARY KEY,
    pipeline_id    VARCHAR(64) NOT NULL REFERENCES t_ingestion_pipeline(id) ON DELETE CASCADE,
    node_id        VARCHAR(128) NOT NULL,
    node_type      VARCHAR(64) NOT NULL,
    next_node_id   VARCHAR(128) NOT NULL DEFAULT '',
    settings_json  JSONB NOT NULL DEFAULT '{}'::jsonb,
    condition_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by     VARCHAR(128) NOT NULL DEFAULT 'rd-bot',
    updated_by     VARCHAR(128) NOT NULL DEFAULT 'rd-bot',
    create_time    TIMESTAMP NOT NULL DEFAULT now(),
    update_time    TIMESTAMP NOT NULL DEFAULT now(),
    deleted        SMALLINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_t_ingestion_pipeline_node_pipeline
    ON t_ingestion_pipeline_node (pipeline_id, id) WHERE deleted = 0;

ALTER TABLE t_ingestion_pipeline_node
    ALTER COLUMN node_id TYPE VARCHAR(128),
    ALTER COLUMN pipeline_id TYPE VARCHAR(64),
    ALTER COLUMN node_type TYPE VARCHAR(64),
    ALTER COLUMN next_node_id TYPE VARCHAR(128);

-- 项目管理：系统可交付项目及其仓库配置。项目配置是生产共享状态，禁止使用 in-memory 兜底。
CREATE TABLE IF NOT EXISTS rd_projects (
    id              BIGINT PRIMARY KEY,
    project_key     VARCHAR(128) NOT NULL,
    name            TEXT NOT NULL,
    description     TEXT NOT NULL DEFAULT '',
    repository_url  TEXT NOT NULL,
    repo_owner      VARCHAR(256) NOT NULL DEFAULT '',
    repo_name       VARCHAR(256) NOT NULL DEFAULT '',
    default_branch  VARCHAR(256) NOT NULL DEFAULT 'main',
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_rd_projects_project_key_active
    ON rd_projects (project_key) WHERE deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_rd_projects_enabled ON rd_projects (enabled, deleted, updated_at);
CREATE INDEX IF NOT EXISTS idx_rd_projects_repo ON rd_projects (repo_owner, repo_name);

CREATE TABLE IF NOT EXISTS rd_tasks (
    id                 BIGINT PRIMARY KEY,
    task_type          VARCHAR(64) NOT NULL,
    ticket_id          VARCHAR(256) NOT NULL DEFAULT '',
    ticket_title       TEXT NOT NULL DEFAULT '',
    priority           VARCHAR(16) NOT NULL DEFAULT 'P2',
    status             VARCHAR(32) NOT NULL,
    message_id         VARCHAR(256) NOT NULL DEFAULT '',
    title              TEXT NOT NULL DEFAULT '',
    prompt_snapshot    TEXT NOT NULL DEFAULT '',
    execution_result_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    pull_request_url   TEXT NOT NULL DEFAULT '',
    error_message      TEXT NOT NULL DEFAULT '',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rd_tasks_type_status ON rd_tasks (task_type, status, updated_at);
CREATE INDEX IF NOT EXISTS idx_rd_tasks_ticket ON rd_tasks (ticket_id);

-- 任务管理：新增 paused 列承载管理台暂停标记（逻辑删复用 status='DELETED'，不新增列）。
ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS paused BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS source_type VARCHAR(32) NOT NULL DEFAULT '';
ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS source_id VARCHAR(256) NOT NULL DEFAULT '';
ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS source_url TEXT NOT NULL DEFAULT '';
ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS project_id VARCHAR(128) NOT NULL DEFAULT '';
ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS project_key VARCHAR(128) NOT NULL DEFAULT '';
ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS project_name TEXT NOT NULL DEFAULT '';
ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS repository_url TEXT NOT NULL DEFAULT '';
ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS repo_owner VARCHAR(256) NOT NULL DEFAULT '';
ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS repo_name VARCHAR(256) NOT NULL DEFAULT '';
ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS base_branch VARCHAR(256) NOT NULL DEFAULT '';
ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS work_branch VARCHAR(256) NOT NULL DEFAULT '';
ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS expected_result TEXT NOT NULL DEFAULT '';
ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS acceptance_criteria_json JSONB NOT NULL DEFAULT '[]'::jsonb;
CREATE INDEX IF NOT EXISTS idx_rd_tasks_project ON rd_tasks (project_id, task_type, updated_at);

-- 任务管理：状态事件 append-only 表，承载全链路时间线（含耗时与触发来源）。
CREATE TABLE IF NOT EXISTS rd_task_status_events (
    id            BIGINT PRIMARY KEY,
    task_id       BIGINT NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    title         TEXT         NOT NULL DEFAULT '',
    message       TEXT         NOT NULL DEFAULT '',
    entered_at    TIMESTAMPTZ  NOT NULL,
    duration_ms   BIGINT       NOT NULL DEFAULT 0,
    trigger       VARCHAR(16)  NOT NULL DEFAULT 'SYSTEM'
);

CREATE INDEX IF NOT EXISTS idx_rd_task_events_task ON rd_task_status_events (task_id, entered_at);

-- 任务管理：需求文档、本地上传和 Feishu 文档等输入材料。
CREATE TABLE IF NOT EXISTS rd_task_materials (
    id                    BIGINT PRIMARY KEY,
    task_id               BIGINT NOT NULL,
    material_type         VARCHAR(64) NOT NULL,
    source_type           VARCHAR(32) NOT NULL,
    title                 TEXT NOT NULL DEFAULT '',
    source_uri            TEXT NOT NULL DEFAULT '',
    mime_type             VARCHAR(128) NOT NULL DEFAULT '',
    content_hash          VARCHAR(128) NOT NULL DEFAULT '',
    content_preview       TEXT NOT NULL DEFAULT '',
    artifact_uri          TEXT NOT NULL DEFAULT '',
    knowledge_document_id VARCHAR(64) NOT NULL DEFAULT '',
    revision_id           VARCHAR(256) NOT NULL DEFAULT '',
    metadata_json         JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rd_task_materials_task ON rd_task_materials (task_id, created_at);
CREATE INDEX IF NOT EXISTS idx_rd_task_materials_source ON rd_task_materials (source_type, source_uri);

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

CREATE TABLE IF NOT EXISTS repair_assets (
    id                 BIGINT PRIMARY KEY,
    repair_record_id   BIGINT NOT NULL REFERENCES repair_records(id) ON DELETE CASCADE,
    asset_type         VARCHAR(64) NOT NULL,
    title              TEXT NOT NULL DEFAULT '',
    summary            TEXT NOT NULL DEFAULT '',
    content_json       JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_artifact_id BIGINT NULL REFERENCES repair_record_artifacts(id) ON DELETE SET NULL,
    reusable           BOOLEAN NOT NULL DEFAULT false,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_repair_assets_record ON repair_assets (repair_record_id, created_at);
CREATE INDEX IF NOT EXISTS idx_repair_assets_type ON repair_assets (asset_type, created_at);
CREATE INDEX IF NOT EXISTS idx_repair_assets_reusable ON repair_assets (reusable, asset_type);

-- P3 governance: append-only audit events for ticket, queue, RAG, execution, PR and manual recovery steps.
CREATE TABLE IF NOT EXISTS repair_audit_events (
    id               BIGSERIAL PRIMARY KEY,
    repair_record_id VARCHAR(128) NOT NULL DEFAULT '',
    task_id          VARCHAR(128) NOT NULL DEFAULT '',
    ticket_id        VARCHAR(256) NOT NULL DEFAULT '',
    event_type       VARCHAR(64) NOT NULL,
    external_system  VARCHAR(64) NOT NULL DEFAULT '',
    summary          TEXT NOT NULL DEFAULT '',
    metadata_json    JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_repair_audit_events_record ON repair_audit_events (repair_record_id, created_at);
CREATE INDEX IF NOT EXISTS idx_repair_audit_events_ticket ON repair_audit_events (ticket_id, created_at);
CREATE INDEX IF NOT EXISTS idx_repair_audit_events_type ON repair_audit_events (event_type, created_at);

-- P3 governance: capped queue retry failures and manual replay state.
CREATE TABLE IF NOT EXISTS repair_queue_dead_letters (
    id                VARCHAR(128) PRIMARY KEY,
    ticket_id         VARCHAR(256) NOT NULL,
    trace_id          VARCHAR(128) NOT NULL DEFAULT '',
    source            VARCHAR(64) NOT NULL DEFAULT '',
    event_id          VARCHAR(256) NOT NULL DEFAULT '',
    event_type        VARCHAR(128) NOT NULL DEFAULT '',
    original_attempt  INTEGER NOT NULL DEFAULT 0,
    reason            TEXT NOT NULL DEFAULT '',
    message_json      JSONB NOT NULL DEFAULT '{}'::jsonb,
    replayed          BOOLEAN NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    replayed_at       TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_repair_queue_dead_letters_ticket ON repair_queue_dead_letters (ticket_id, created_at);
CREATE INDEX IF NOT EXISTS idx_repair_queue_dead_letters_replayed ON repair_queue_dead_letters (replayed, created_at);

-- P3 governance: knowledge refresh health metrics for operations views.
CREATE TABLE IF NOT EXISTS knowledge_refresh_metrics (
    id                  BIGSERIAL PRIMARY KEY,
    document_id          VARCHAR(128) NOT NULL DEFAULT '',
    knowledge_base_id    VARCHAR(128) NOT NULL DEFAULT '',
    source_type          VARCHAR(64) NOT NULL DEFAULT '',
    source_name          TEXT NOT NULL DEFAULT '',
    success              BOOLEAN NOT NULL,
    old_chunk_count      INTEGER NOT NULL DEFAULT 0,
    new_chunk_count      INTEGER NOT NULL DEFAULT 0,
    duration_ms          BIGINT NOT NULL DEFAULT 0,
    error_message        TEXT NOT NULL DEFAULT '',
    metadata_json        JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_refresh_metrics_doc ON knowledge_refresh_metrics (document_id, created_at);
CREATE INDEX IF NOT EXISTS idx_knowledge_refresh_metrics_success ON knowledge_refresh_metrics (success, created_at);
