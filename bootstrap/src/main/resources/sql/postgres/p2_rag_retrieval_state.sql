CREATE TABLE IF NOT EXISTS rd_rag_retrieval_runs (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    consumer_type VARCHAR(32) NOT NULL,
    role VARCHAR(64) NOT NULL DEFAULT '',
    stage_run_id BIGINT,
    attempt_no INTEGER NOT NULL,
    parent_run_id BIGINT,
    idempotency_key VARCHAR(384) NOT NULL,
    status VARCHAR(32) NOT NULL,
    knowledge_base_ids_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    query_hash VARCHAR(128) NOT NULL DEFAULT '',
    query_preview TEXT NOT NULL DEFAULT '',
    current_iteration INTEGER NOT NULL DEFAULT 0,
    max_iterations INTEGER NOT NULL DEFAULT 3,
    context_budget_chars INTEGER NOT NULL DEFAULT 18000,
    candidate_count INTEGER NOT NULL DEFAULT 0,
    selected_evidence_count INTEGER NOT NULL DEFAULT 0,
    quality_decision VARCHAR(48) NOT NULL DEFAULT '',
    stop_reason TEXT NOT NULL DEFAULT '',
    error_category VARCHAR(128) NOT NULL DEFAULT '',
    error_message TEXT NOT NULL DEFAULT '',
    lease_owner VARCHAR(128) NOT NULL DEFAULT '',
    lease_until TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_rd_rag_retrieval_run_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_rd_rag_retrieval_runs_task
    ON rd_rag_retrieval_runs (task_id, created_at, attempt_no);
CREATE INDEX IF NOT EXISTS idx_rd_rag_retrieval_runs_status
    ON rd_rag_retrieval_runs (status, lease_until, updated_at);

CREATE TABLE IF NOT EXISTS rd_rag_retrieval_events (
    id BIGINT PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES rd_rag_retrieval_runs(id) ON DELETE CASCADE,
    from_status VARCHAR(32) NOT NULL,
    to_status VARCHAR(32) NOT NULL,
    trigger VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
    message TEXT NOT NULL DEFAULT '',
    error_category VARCHAR(128) NOT NULL DEFAULT '',
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rd_rag_retrieval_events_run
    ON rd_rag_retrieval_events (run_id, occurred_at, id);

CREATE TABLE IF NOT EXISTS rd_rag_retrieval_steps (
    id BIGINT PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES rd_rag_retrieval_runs(id) ON DELETE CASCADE,
    iteration_no INTEGER NOT NULL DEFAULT 0,
    step_type VARCHAR(48) NOT NULL,
    step_key VARCHAR(256) NOT NULL,
    channel_name VARCHAR(128) NOT NULL DEFAULT '',
    required BOOLEAN NOT NULL DEFAULT FALSE,
    step_attempt_no INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    input_artifact_id BIGINT,
    output_artifact_id BIGINT,
    candidate_count INTEGER NOT NULL DEFAULT 0,
    selected_count INTEGER NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    error_category VARCHAR(128) NOT NULL DEFAULT '',
    error_message TEXT NOT NULL DEFAULT '',
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_rd_rag_retrieval_step_attempt UNIQUE (run_id, iteration_no, step_key, step_attempt_no)
);

CREATE TABLE IF NOT EXISTS rd_rag_retrieval_artifacts (
    id BIGINT PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES rd_rag_retrieval_runs(id) ON DELETE CASCADE,
    step_id BIGINT,
    artifact_type VARCHAR(64) NOT NULL,
    artifact_uri TEXT NOT NULL DEFAULT '',
    content_preview TEXT NOT NULL DEFAULT '',
    content_hash VARCHAR(128) NOT NULL DEFAULT '',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    redacted BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS knowledge_map_nodes (
    id BIGINT PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    parent_id BIGINT,
    node_type VARCHAR(32) NOT NULL,
    path TEXT NOT NULL,
    title TEXT NOT NULL DEFAULT '',
    summary TEXT NOT NULL DEFAULT '',
    document_id BIGINT REFERENCES knowledge_documents(id) ON DELETE SET NULL,
    chunk_start INTEGER,
    chunk_end INTEGER,
    revision_id VARCHAR(128) NOT NULL DEFAULT '',
    checksum VARCHAR(128) NOT NULL DEFAULT '',
    token_estimate INTEGER NOT NULL DEFAULT 0,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_knowledge_map_nodes_path UNIQUE (knowledge_base_id, path, revision_id)
);

ALTER TABLE rd_role_context_packages ADD COLUMN IF NOT EXISTS retrieval_run_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_rd_role_context_packages_retrieval_run
    ON rd_role_context_packages (retrieval_run_id) WHERE retrieval_run_id IS NOT NULL;
