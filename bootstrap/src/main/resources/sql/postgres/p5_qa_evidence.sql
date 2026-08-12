CREATE TABLE IF NOT EXISTS rd_qa_validation_profiles (
    scope_type VARCHAR(16) NOT NULL,
    scope_id BIGINT NOT NULL,
    mode VARCHAR(16) NOT NULL,
    base_url TEXT NOT NULL DEFAULT '',
    start_command TEXT NOT NULL DEFAULT '',
    health_path TEXT NOT NULL DEFAULT '',
    allowed_hosts_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    regression_commands_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (scope_type, scope_id),
    CONSTRAINT chk_rd_qa_profile_scope CHECK (scope_type IN ('PROJECT', 'TASK')),
    CONSTRAINT chk_rd_qa_profile_mode CHECK (mode IN ('AUTO', 'REQUIRED', 'DISABLED'))
);

CREATE TABLE IF NOT EXISTS rd_qa_evidence_objects (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    stage_run_id BIGINT NOT NULL,
    artifact_id BIGINT NOT NULL,
    artifact_type VARCHAR(64) NOT NULL,
    artifact_name TEXT NOT NULL,
    object_uri TEXT NOT NULL,
    content_type VARCHAR(160) NOT NULL DEFAULT 'application/octet-stream',
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NULL,
    UNIQUE (task_id, stage_run_id, artifact_id)
);

CREATE INDEX IF NOT EXISTS idx_rd_qa_evidence_task
    ON rd_qa_evidence_objects (task_id, stage_run_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_rd_qa_evidence_expiry
    ON rd_qa_evidence_objects (expires_at)
    WHERE expires_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS rd_host_assertion_bundles (
    task_id BIGINT NOT NULL,
    stage_run_id BIGINT NOT NULL,
    scope VARCHAR(16) NOT NULL,
    canonical_specs_json JSONB NOT NULL,
    content_hash TEXT NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id, stage_run_id, scope),
    CONSTRAINT chk_rd_host_assertion_bundle_scope CHECK (scope IN ('CURRENT', 'REGRESSION')),
    CONSTRAINT chk_rd_host_assertion_bundle_version CHECK (version > 0)
);
