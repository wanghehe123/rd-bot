CREATE TABLE IF NOT EXISTS rd_evaluation_runs (
    id BIGINT PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    attempt_no INTEGER NOT NULL DEFAULT 1,
    parent_run_id BIGINT,
    status VARCHAR(32) NOT NULL,
    phase_message TEXT NOT NULL DEFAULT '',
    progress_percent INTEGER NOT NULL DEFAULT 0,
    config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    sample_count INTEGER NOT NULL DEFAULT 0,
    passed_sample_count INTEGER NOT NULL DEFAULT 0,
    failed_sample_count INTEGER NOT NULL DEFAULT 0,
    overall_passed BOOLEAN NOT NULL DEFAULT FALSE,
    metrics_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    error_category VARCHAR(128) NOT NULL DEFAULT '',
    error_message TEXT NOT NULL DEFAULT '',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_evaluation_run_attempt CHECK (attempt_no > 0),
    CONSTRAINT ck_evaluation_run_progress CHECK (progress_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_evaluation_run_counts CHECK (
        sample_count >= 0 AND passed_sample_count >= 0 AND failed_sample_count >= 0
    )
);

CREATE INDEX IF NOT EXISTS idx_evaluation_run_created
    ON rd_evaluation_runs (created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_evaluation_run_status
    ON rd_evaluation_runs (status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_evaluation_run_parent
    ON rd_evaluation_runs (parent_run_id, attempt_no);
CREATE INDEX IF NOT EXISTS idx_evaluation_run_history_source_dataset
    ON rd_evaluation_runs ((config_json ->> 'source'), (config_json ->> 'datasetId'), created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_evaluation_run_history_gate
    ON rd_evaluation_runs ((metrics_json #>> '{summary,gateStatus}'));
CREATE INDEX IF NOT EXISTS idx_evaluation_run_history_judge
    ON rd_evaluation_runs ((metrics_json #>> '{summary,judgeStatus}'));

CREATE TABLE IF NOT EXISTS rd_evaluation_events (
    id BIGINT PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES rd_evaluation_runs(id) ON DELETE CASCADE,
    from_status VARCHAR(32) NOT NULL DEFAULT '',
    to_status VARCHAR(32) NOT NULL,
    message TEXT NOT NULL DEFAULT '',
    error_category VARCHAR(128) NOT NULL DEFAULT '',
    error_message TEXT NOT NULL DEFAULT '',
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_evaluation_event_run
    ON rd_evaluation_events (run_id, occurred_at, id);

CREATE TABLE IF NOT EXISTS rd_evaluation_artifacts (
    id BIGINT PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES rd_evaluation_runs(id) ON DELETE CASCADE,
    artifact_type VARCHAR(32) NOT NULL,
    artifact_uri TEXT NOT NULL DEFAULT '',
    content_preview TEXT NOT NULL DEFAULT '',
    content_hash VARCHAR(128) NOT NULL DEFAULT '',
    size_bytes BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_evaluation_artifact_type UNIQUE (run_id, artifact_type),
    CONSTRAINT ck_evaluation_artifact_size CHECK (size_bytes >= 0)
);

CREATE INDEX IF NOT EXISTS idx_evaluation_artifact_run
    ON rd_evaluation_artifacts (run_id, created_at, id);

CREATE TABLE IF NOT EXISTS rd_evaluation_trials (
    id VARCHAR(200) PRIMARY KEY,
    campaign_id BIGINT NOT NULL REFERENCES rd_evaluation_runs(id) ON DELETE CASCADE,
    case_id VARCHAR(200) NOT NULL,
    arm VARCHAR(8) NOT NULL,
    replicate_no INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    verdict VARCHAR(64) NOT NULL DEFAULT 'PENDING',
    attempt_no INTEGER NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    lease_owner VARCHAR(200) NOT NULL DEFAULT '',
    lease_expires_at TIMESTAMPTZ,
    error_category VARCHAR(128) NOT NULL DEFAULT '',
    error_message TEXT NOT NULL DEFAULT '',
    frozen_input_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    runtime_attestation_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    patch_summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    metrics_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_evaluation_trial_cell UNIQUE (campaign_id, case_id, arm, replicate_no),
    CONSTRAINT ck_evaluation_trial_replicate CHECK (replicate_no IN (0, 1)),
    CONSTRAINT ck_evaluation_trial_attempt CHECK (attempt_no >= 1),
    CONSTRAINT ck_evaluation_trial_version CHECK (version >= 0)
);

CREATE INDEX IF NOT EXISTS idx_evaluation_trial_campaign_status
    ON rd_evaluation_trials (campaign_id, status, created_at, id);
CREATE INDEX IF NOT EXISTS idx_evaluation_trial_expired_lease
    ON rd_evaluation_trials (campaign_id, lease_expires_at)
    WHERE status = 'PREPARING';

CREATE TABLE IF NOT EXISTS rd_evaluation_trial_events (
    id BIGINT PRIMARY KEY,
    campaign_id BIGINT NOT NULL REFERENCES rd_evaluation_runs(id) ON DELETE CASCADE,
    trial_id VARCHAR(200) NOT NULL REFERENCES rd_evaluation_trials(id) ON DELETE CASCADE,
    from_status VARCHAR(32) NOT NULL DEFAULT '',
    to_status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    message TEXT NOT NULL DEFAULT '',
    error_category VARCHAR(128) NOT NULL DEFAULT '',
    error_message TEXT NOT NULL DEFAULT '',
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_evaluation_trial_event_timeline
    ON rd_evaluation_trial_events (trial_id, occurred_at, id);
