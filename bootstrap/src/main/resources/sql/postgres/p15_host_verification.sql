ALTER TABLE rd_qa_validation_profiles
    ADD COLUMN IF NOT EXISTS build_commands_json JSONB,
    ADD COLUMN IF NOT EXISTS static_commands_json JSONB;

CREATE TABLE IF NOT EXISTS rd_host_verification_runs (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    coding_stage_run_id BIGINT NOT NULL,
    parent_run_id BIGINT NOT NULL DEFAULT 0,
    attempt_no INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    docs_only BOOLEAN NOT NULL DEFAULT FALSE,
    failure_category VARCHAR(32) NOT NULL DEFAULT '',
    error_message TEXT NOT NULL DEFAULT '',
    remediation_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    UNIQUE (task_id, attempt_no),
    CONSTRAINT chk_rd_host_verify_status CHECK (status IN (
        'CREATED','PREPARING','BUILDING','STATIC_CHECKING',
        'SUCCEEDED','FAILED_RETRYABLE','FAILED_NEEDS_HUMAN',
        'SKIPPED_DOCS_ONLY','CANCELLED')),
    CONSTRAINT chk_rd_host_verify_attempt CHECK (attempt_no >= 1),
    CONSTRAINT chk_rd_host_verify_remediation CHECK (remediation_count >= 0)
);

CREATE TABLE IF NOT EXISTS rd_host_verification_steps (
    run_id BIGINT NOT NULL,
    step VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    commands_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    exit_code INT,
    duration_millis BIGINT NOT NULL DEFAULT 0,
    log_artifact_id BIGINT NOT NULL DEFAULT 0,
    error_message TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (run_id, step),
    CONSTRAINT chk_rd_host_verify_step CHECK (step IN ('BUILD','STATIC'))
);

ALTER TABLE rd_task_failure_provenance
    ADD COLUMN IF NOT EXISTS failed_verification_run_id BIGINT
        REFERENCES rd_host_verification_runs(id) ON DELETE RESTRICT;

CREATE TABLE IF NOT EXISTS rd_host_verification_artifacts (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    run_id BIGINT NOT NULL,
    artifact_type VARCHAR(64) NOT NULL,
    relative_path TEXT NOT NULL,
    object_uri TEXT NOT NULL,
    content_type VARCHAR(160) NOT NULL DEFAULT 'text/plain',
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, relative_path)
);
