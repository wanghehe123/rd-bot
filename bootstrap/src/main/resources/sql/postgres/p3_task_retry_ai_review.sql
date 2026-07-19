CREATE TABLE IF NOT EXISTS rd_task_retry_checkpoints (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    failure_phase VARCHAR(48) NOT NULL,
    retry_from_role VARCHAR(64) NOT NULL DEFAULT '',
    failed_stage_run_id BIGINT,
    failed_retrieval_run_id BIGINT,
    failed_ai_review_run_id BIGINT,
    attempt_no INTEGER NOT NULL,
    idempotency_key VARCHAR(512) NOT NULL,
    source_task_status VARCHAR(48) NOT NULL,
    source_task_version BIGINT NOT NULL,
    operator_note TEXT NOT NULL DEFAULT '',
    evidence_material_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(32) NOT NULL,
    reason TEXT NOT NULL DEFAULT '',
    error_message TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_task_retry_checkpoint_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uk_task_retry_checkpoint_attempt UNIQUE (task_id, attempt_no)
);

ALTER TABLE rd_task_retry_checkpoints
    ADD COLUMN IF NOT EXISTS operator_note TEXT NOT NULL DEFAULT '';
ALTER TABLE rd_task_retry_checkpoints
    ADD COLUMN IF NOT EXISTS evidence_material_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX IF NOT EXISTS idx_task_retry_checkpoint_task
    ON rd_task_retry_checkpoints (task_id, attempt_no, created_at);
CREATE INDEX IF NOT EXISTS idx_task_retry_checkpoint_active
    ON rd_task_retry_checkpoints (task_id, status, updated_at)
    WHERE status IN ('CREATED', 'DISPATCHED');

CREATE TABLE IF NOT EXISTS rd_ai_review_runs (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    attempt_no INTEGER NOT NULL,
    parent_run_id BIGINT,
    status VARCHAR(40) NOT NULL,
    model_name VARCHAR(256) NOT NULL DEFAULT '',
    package_hash VARCHAR(128) NOT NULL DEFAULT '',
    decision VARCHAR(32) NOT NULL DEFAULT '',
    score INTEGER NOT NULL DEFAULT 0,
    retry_from_role VARCHAR(64) NOT NULL DEFAULT '',
    summary TEXT NOT NULL DEFAULT '',
    error_category VARCHAR(128) NOT NULL DEFAULT '',
    error_message TEXT NOT NULL DEFAULT '',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_ai_review_run_attempt UNIQUE (task_id, attempt_no),
    CONSTRAINT ck_ai_review_run_score CHECK (score BETWEEN 0 AND 100)
);

CREATE INDEX IF NOT EXISTS idx_ai_review_run_task
    ON rd_ai_review_runs (task_id, attempt_no, created_at);
CREATE INDEX IF NOT EXISTS idx_ai_review_run_status
    ON rd_ai_review_runs (status, updated_at);

CREATE TABLE IF NOT EXISTS rd_ai_review_events (
    id BIGINT PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES rd_ai_review_runs(id) ON DELETE CASCADE,
    from_status VARCHAR(40) NOT NULL,
    to_status VARCHAR(40) NOT NULL,
    trigger VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
    message TEXT NOT NULL DEFAULT '',
    error_category VARCHAR(128) NOT NULL DEFAULT '',
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ai_review_event_run
    ON rd_ai_review_events (run_id, occurred_at, id);

CREATE TABLE IF NOT EXISTS rd_ai_review_artifacts (
    id BIGINT PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES rd_ai_review_runs(id) ON DELETE CASCADE,
    artifact_type VARCHAR(64) NOT NULL,
    artifact_uri TEXT NOT NULL DEFAULT '',
    content_preview TEXT NOT NULL DEFAULT '',
    content_hash VARCHAR(128) NOT NULL DEFAULT '',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    redacted BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ai_review_artifact_run
    ON rd_ai_review_artifacts (run_id, created_at, id);
