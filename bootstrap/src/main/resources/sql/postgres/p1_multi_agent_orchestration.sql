-- P1 multi-agent orchestration: role-scoped contexts, stage runs, artifacts, skills and experience.

CREATE TABLE IF NOT EXISTS rd_role_context_packages (
    id                    BIGINT PRIMARY KEY,
    task_id               BIGINT NOT NULL REFERENCES rd_tasks(id) ON DELETE CASCADE,
    role                  VARCHAR(64) NOT NULL,
    package_version       INTEGER NOT NULL DEFAULT 1,
    evidence_json         JSONB NOT NULL DEFAULT '[]'::jsonb,
    acceptance_json       JSONB NOT NULL DEFAULT '[]'::jsonb,
    risk_hints_json       JSONB NOT NULL DEFAULT '[]'::jsonb,
    context_budget_json   JSONB NOT NULL DEFAULT '{}'::jsonb,
    omitted_evidence_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    content_hash          VARCHAR(128) NOT NULL DEFAULT '',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rd_role_context_packages_task
    ON rd_role_context_packages (task_id, role, package_version);

CREATE TABLE IF NOT EXISTS rd_agent_stage_runs (
    id                     BIGINT PRIMARY KEY,
    task_id                BIGINT NOT NULL REFERENCES rd_tasks(id) ON DELETE CASCADE,
    role                   VARCHAR(64) NOT NULL,
    status                 VARCHAR(64) NOT NULL,
    attempt_no             INTEGER NOT NULL,
    idempotency_key        VARCHAR(256) NOT NULL,
    provider_name          VARCHAR(128) NOT NULL DEFAULT '',
    provider_attempts_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    context_package_id     BIGINT REFERENCES rd_role_context_packages(id) ON DELETE SET NULL,
    prompt_artifact_id     BIGINT,
    result_artifact_id     BIGINT,
    review_result_json     JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_category         VARCHAR(128) NOT NULL DEFAULT '',
    error_message          TEXT NOT NULL DEFAULT '',
    started_at             TIMESTAMPTZ,
    finished_at            TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_rd_agent_stage_runs_attempt
    ON rd_agent_stage_runs (task_id, role, attempt_no);

CREATE UNIQUE INDEX IF NOT EXISTS uk_rd_agent_stage_runs_idempotency
    ON rd_agent_stage_runs (task_id, role, idempotency_key);

CREATE INDEX IF NOT EXISTS idx_rd_agent_stage_runs_task_status
    ON rd_agent_stage_runs (task_id, status, updated_at);

CREATE TABLE IF NOT EXISTS rd_agent_stage_artifacts (
    id               BIGINT PRIMARY KEY,
    stage_run_id     BIGINT NOT NULL REFERENCES rd_agent_stage_runs(id) ON DELETE CASCADE,
    task_id          BIGINT NOT NULL REFERENCES rd_tasks(id) ON DELETE CASCADE,
    role             VARCHAR(64) NOT NULL,
    artifact_type    VARCHAR(64) NOT NULL,
    artifact_uri     TEXT NOT NULL DEFAULT '',
    summary          TEXT NOT NULL DEFAULT '',
    content_preview  TEXT NOT NULL DEFAULT '',
    content_hash     VARCHAR(128) NOT NULL DEFAULT '',
    metadata_json    JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE rd_agent_stage_artifacts
    ADD COLUMN IF NOT EXISTS content_preview TEXT NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_rd_agent_stage_artifacts_stage
    ON rd_agent_stage_artifacts (stage_run_id, artifact_type);

CREATE INDEX IF NOT EXISTS idx_rd_agent_stage_artifacts_task
    ON rd_agent_stage_artifacts (task_id, role, created_at);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_rd_agent_stage_runs_prompt_artifact'
    ) THEN
        ALTER TABLE rd_agent_stage_runs
            ADD CONSTRAINT fk_rd_agent_stage_runs_prompt_artifact
            FOREIGN KEY (prompt_artifact_id) REFERENCES rd_agent_stage_artifacts(id) ON DELETE SET NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_rd_agent_stage_runs_result_artifact'
    ) THEN
        ALTER TABLE rd_agent_stage_runs
            ADD CONSTRAINT fk_rd_agent_stage_runs_result_artifact
            FOREIGN KEY (result_artifact_id) REFERENCES rd_agent_stage_artifacts(id) ON DELETE SET NULL;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS rd_agent_stage_events (
    id           BIGINT PRIMARY KEY,
    stage_run_id BIGINT NOT NULL REFERENCES rd_agent_stage_runs(id) ON DELETE CASCADE,
    task_id      BIGINT NOT NULL REFERENCES rd_tasks(id) ON DELETE CASCADE,
    role         VARCHAR(64) NOT NULL,
    status       VARCHAR(64) NOT NULL,
    message      TEXT NOT NULL DEFAULT '',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    entered_at   TIMESTAMPTZ NOT NULL,
    duration_ms  BIGINT NOT NULL DEFAULT 0,
    trigger      VARCHAR(32) NOT NULL DEFAULT 'SYSTEM'
);

CREATE INDEX IF NOT EXISTS idx_rd_agent_stage_events_stage
    ON rd_agent_stage_events (stage_run_id, entered_at);

CREATE INDEX IF NOT EXISTS idx_rd_agent_stage_events_task
    ON rd_agent_stage_events (task_id, entered_at);

CREATE TABLE IF NOT EXISTS rd_agent_skill_installations (
    id              BIGINT PRIMARY KEY,
    task_id          BIGINT NOT NULL REFERENCES rd_tasks(id) ON DELETE CASCADE,
    stage_run_id     BIGINT REFERENCES rd_agent_stage_runs(id) ON DELETE SET NULL,
    role             VARCHAR(64) NOT NULL,
    skill_id         VARCHAR(256) NOT NULL,
    skill_version    VARCHAR(128) NOT NULL DEFAULT '',
    source_uri       TEXT NOT NULL DEFAULT '',
    checksum         VARCHAR(128) NOT NULL DEFAULT '',
    allowed_roles_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    risk_level       VARCHAR(32) NOT NULL DEFAULT 'LOW',
    install_path     TEXT NOT NULL DEFAULT '',
    policy_json      JSONB NOT NULL DEFAULT '{}'::jsonb,
    installed_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rd_agent_skill_installations_task
    ON rd_agent_skill_installations (task_id, role, installed_at);

CREATE INDEX IF NOT EXISTS idx_rd_agent_skill_installations_skill
    ON rd_agent_skill_installations (skill_id, skill_version);

CREATE TABLE IF NOT EXISTS rd_experience_entries (
    id                    BIGINT PRIMARY KEY,
    task_id                BIGINT NOT NULL REFERENCES rd_tasks(id) ON DELETE CASCADE,
    stage_run_id           BIGINT REFERENCES rd_agent_stage_runs(id) ON DELETE SET NULL,
    source_artifact_id     BIGINT REFERENCES rd_agent_stage_artifacts(id) ON DELETE SET NULL,
    role                  VARCHAR(64) NOT NULL DEFAULT '',
    experience_type        VARCHAR(64) NOT NULL,
    title                  TEXT NOT NULL DEFAULT '',
    summary                TEXT NOT NULL DEFAULT '',
    content_json           JSONB NOT NULL DEFAULT '{}'::jsonb,
    content_hash           VARCHAR(128) NOT NULL DEFAULT '',
    reusable               BOOLEAN NOT NULL DEFAULT FALSE,
    failure                BOOLEAN NOT NULL DEFAULT FALSE,
    redacted               BOOLEAN NOT NULL DEFAULT TRUE,
    project_id             VARCHAR(128) NOT NULL DEFAULT '',
    repository_fingerprint VARCHAR(512) NOT NULL DEFAULT '',
    intent_id              VARCHAR(128) NOT NULL DEFAULT '',
    tags_json              JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_revision        VARCHAR(256) NOT NULL DEFAULT '',
    evidence_quality       DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    applicable_roles_json  JSONB NOT NULL DEFAULT '[]'::jsonb,
    ingestion_task_id      BIGINT REFERENCES ingestion_tasks(id) ON DELETE SET NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_rd_experience_entries_evidence_quality
        CHECK (evidence_quality >= 0.0 AND evidence_quality <= 1.0)
);

CREATE INDEX IF NOT EXISTS idx_rd_experience_entries_task
    ON rd_experience_entries (task_id, created_at);

CREATE INDEX IF NOT EXISTS idx_rd_experience_entries_type
    ON rd_experience_entries (experience_type, reusable, created_at);

CREATE INDEX IF NOT EXISTS idx_rd_experience_entries_role
    ON rd_experience_entries (role, experience_type, created_at);

CREATE INDEX IF NOT EXISTS idx_rd_experience_entries_scope
    ON rd_experience_entries (project_id, repository_fingerprint, reusable, created_at DESC);

CREATE TABLE IF NOT EXISTS rd_requirement_delivery_jobs (
    id            BIGINT PRIMARY KEY,
    task_id       BIGINT NOT NULL UNIQUE REFERENCES rd_tasks(id) ON DELETE CASCADE,
    status        VARCHAR(64) NOT NULL,
    attempt_no    INTEGER NOT NULL DEFAULT 0,
    max_attempts  INTEGER NOT NULL DEFAULT 3,
    lease_owner   VARCHAR(256) NOT NULL DEFAULT '',
    lease_until   TIMESTAMPTZ,
    error_message TEXT NOT NULL DEFAULT '',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_rd_requirement_delivery_job_attempts
        CHECK (attempt_no >= 0 AND max_attempts > 0)
);

CREATE INDEX IF NOT EXISTS idx_rd_requirement_delivery_jobs_recovery
    ON rd_requirement_delivery_jobs (status, lease_until, updated_at);
