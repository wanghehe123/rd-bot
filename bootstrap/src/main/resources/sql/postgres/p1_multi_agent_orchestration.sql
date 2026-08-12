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
          AND conrelid = 'rd_agent_stage_runs'::regclass
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
          AND conrelid = 'rd_agent_stage_runs'::regclass
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

-- Bounded persistent stage scheduler. Each role/stage action is independently leased and retried;
-- task_version/fencing_token bind late results to the snapshot that created the command.
CREATE TABLE IF NOT EXISTS rd_requirement_stage_commands (
    id                    BIGINT PRIMARY KEY,
    task_id               BIGINT NOT NULL REFERENCES rd_tasks(id) ON DELETE CASCADE,
    task_version          BIGINT NOT NULL DEFAULT 0,
    fencing_token         BIGINT NOT NULL DEFAULT 1,
    role                  VARCHAR(64) NOT NULL DEFAULT '',
    stage                 VARCHAR(128) NOT NULL,
    policy_run_id         BIGINT,
    attempt_no            INTEGER NOT NULL DEFAULT 0,
    max_attempts          INTEGER NOT NULL DEFAULT 3,
    deadline_at           TIMESTAMPTZ,
    resource_class        VARCHAR(32) NOT NULL DEFAULT 'GENERIC',
    resource_requirements TEXT NOT NULL DEFAULT '',
    project_id            VARCHAR(128) NOT NULL DEFAULT '_default',
    provider_id           VARCHAR(128) NOT NULL DEFAULT '',
    priority_rank         INTEGER NOT NULL DEFAULT 2,
    status                VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    lease_owner           VARCHAR(256) NOT NULL DEFAULT '',
    lease_until           TIMESTAMPTZ,
    next_visible_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error            TEXT NOT NULL DEFAULT '',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_rd_requirement_stage_command_attempts
        CHECK (attempt_no >= 0 AND max_attempts > 0),
    CONSTRAINT ck_rd_requirement_stage_command_priority
        CHECK (priority_rank >= 0),
    CONSTRAINT ck_rd_requirement_stage_command_version
        CHECK (task_version >= 0 AND fencing_token > 0)
);

-- Authoritative, immutable policy ledger. Timeline and task execution results are deliberately
-- excluded from approval authority: an approval binds only these frozen plan/policy digests.
CREATE TABLE IF NOT EXISTS rd_requirement_policy_runs (
    id                     BIGINT PRIMARY KEY,
    task_id                BIGINT NOT NULL REFERENCES rd_tasks(id) ON DELETE CASCADE,
    source_task_version    BIGINT NOT NULL CHECK (source_task_version >= 0),
    source_fence           BIGINT NOT NULL CHECK (source_fence > 0),
    plan_json              JSONB NOT NULL,
    plan_digest            VARCHAR(71) NOT NULL CHECK (plan_digest ~ '^sha256:[0-9a-f]{64}$'),
    policy_json            JSONB,
    policy_digest          VARCHAR(71) CHECK (policy_digest IS NULL OR policy_digest ~ '^sha256:[0-9a-f]{64}$'),
    policy_action          VARCHAR(64) NOT NULL DEFAULT '',
    state                  VARCHAR(32) NOT NULL,
    bound_task_version     BIGINT NOT NULL CHECK (bound_task_version >= 0),
    bound_fence            BIGINT NOT NULL CHECK (bound_fence > 0),
    approval_expected_task_version BIGINT CHECK (approval_expected_task_version >= 0),
    approval_expected_fence BIGINT CHECK (approval_expected_fence > 0),
    approval_request_id    VARCHAR(256),
    approved_by            VARCHAR(256) NOT NULL DEFAULT '',
    note                   TEXT NOT NULL DEFAULT '',
    approved_at            TIMESTAMPTZ,
    approval_resume_command_id BIGINT,
    consumed_by_command_id BIGINT REFERENCES rd_requirement_stage_commands(id) ON DELETE RESTRICT,
    consumed_at            TIMESTAMPTZ,
    ledger_version         BIGINT NOT NULL DEFAULT 0 CHECK (ledger_version >= 0),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_rd_requirement_policy_run_state CHECK (
        state IN ('PLAN_READY', 'POLICY_DECIDED', 'WAITING_APPROVAL', 'APPROVED', 'APPLIED', 'DENIED', 'SUPERSEDED')
    ),
    CONSTRAINT ck_rd_requirement_policy_run_policy_pair CHECK (
        (policy_json IS NULL AND policy_digest IS NULL) OR (policy_json IS NOT NULL AND policy_digest IS NOT NULL)
    ),
    CONSTRAINT ck_rd_requirement_policy_run_approval_expected_pair CHECK (
        (approval_expected_task_version IS NULL AND approval_expected_fence IS NULL)
        OR (approval_expected_task_version IS NOT NULL AND approval_expected_fence IS NOT NULL)
    ),
    CONSTRAINT ck_rd_requirement_policy_run_approval_expected_state CHECK (
        ((state = 'APPROVED' OR (state = 'APPLIED' AND approval_request_id IS NOT NULL))
            AND approval_expected_task_version IS NOT NULL AND approval_expected_fence IS NOT NULL
            AND approval_resume_command_id IS NOT NULL)
        OR (state = 'SUPERSEDED')
        OR ((state <> 'APPROVED' AND (state <> 'APPLIED' OR approval_request_id IS NULL))
            AND approval_expected_task_version IS NULL AND approval_expected_fence IS NULL
            AND approval_resume_command_id IS NULL)
    )
);

-- A policy generation is immutable for the task snapshot/fencing pair that created it.
CREATE UNIQUE INDEX IF NOT EXISTS uk_rd_requirement_policy_runs_generation
    ON rd_requirement_policy_runs (task_id, source_task_version, source_fence);
DROP INDEX IF EXISTS uk_rd_requirement_policy_runs_one_active;
CREATE UNIQUE INDEX uk_rd_requirement_policy_runs_one_active
    ON rd_requirement_policy_runs (task_id)
    WHERE state IN ('PLAN_READY', 'POLICY_DECIDED', 'WAITING_APPROVAL', 'APPROVED');
CREATE UNIQUE INDEX IF NOT EXISTS uk_rd_requirement_policy_runs_approval_request
    ON rd_requirement_policy_runs (task_id, approval_request_id)
    WHERE approval_request_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_rd_requirement_policy_runs_consumed_command
    ON rd_requirement_policy_runs (consumed_by_command_id)
    WHERE consumed_by_command_id IS NOT NULL;
-- Re-running p1 against an already provisioned control plane must add the approval identity
-- columns too; CREATE TABLE IF NOT EXISTS alone would leave the transactional mapper unusable.
ALTER TABLE rd_requirement_policy_runs
    ADD COLUMN IF NOT EXISTS approval_expected_task_version BIGINT,
    ADD COLUMN IF NOT EXISTS approval_expected_fence BIGINT,
    ADD COLUMN IF NOT EXISTS approval_resume_command_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_rd_requirement_policy_run_approval_expected_pair'
                   AND conrelid = 'rd_requirement_policy_runs'::regclass) THEN
        ALTER TABLE rd_requirement_policy_runs
            ADD CONSTRAINT ck_rd_requirement_policy_run_approval_expected_pair CHECK (
                (approval_expected_task_version IS NULL AND approval_expected_fence IS NULL)
                OR (approval_expected_task_version IS NOT NULL AND approval_expected_fence IS NOT NULL)
            );
    END IF;
    ALTER TABLE rd_requirement_policy_runs
        DROP CONSTRAINT IF EXISTS ck_rd_requirement_policy_run_approval_expected_state;
    ALTER TABLE rd_requirement_policy_runs
        ADD CONSTRAINT ck_rd_requirement_policy_run_approval_expected_state CHECK (
            ((state = 'APPROVED' OR (state = 'APPLIED' AND approval_request_id IS NOT NULL))
                AND approval_expected_task_version IS NOT NULL AND approval_expected_fence IS NOT NULL
                AND approval_resume_command_id IS NOT NULL)
            OR state = 'SUPERSEDED'
            OR ((state <> 'APPROVED' AND (state <> 'APPLIED' OR approval_request_id IS NULL))
                AND approval_expected_task_version IS NULL AND approval_expected_fence IS NULL
                AND approval_resume_command_id IS NULL)
        );
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_rd_requirement_policy_run_approval_resume_command'
                   AND conrelid = 'rd_requirement_policy_runs'::regclass) THEN
        ALTER TABLE rd_requirement_policy_runs
            ADD CONSTRAINT fk_rd_requirement_policy_run_approval_resume_command
                FOREIGN KEY (approval_resume_command_id)
                REFERENCES rd_requirement_stage_commands(id) ON DELETE RESTRICT;
    END IF;
END
$$;

-- Upgraded ledgers retain a SUPERSEDED audit snapshot, including historical approval fields.
DO $$
BEGIN
    ALTER TABLE rd_requirement_policy_runs
        DROP CONSTRAINT IF EXISTS ck_rd_requirement_policy_run_state;
    ALTER TABLE rd_requirement_policy_runs
        ADD CONSTRAINT ck_rd_requirement_policy_run_state CHECK (
            state IN ('PLAN_READY', 'POLICY_DECIDED', 'WAITING_APPROVAL', 'APPROVED', 'APPLIED', 'DENIED', 'SUPERSEDED')
        );
    ALTER TABLE rd_requirement_policy_runs
        DROP CONSTRAINT IF EXISTS ck_rd_requirement_policy_run_approval_expected_state;
    ALTER TABLE rd_requirement_policy_runs
        ADD CONSTRAINT ck_rd_requirement_policy_run_approval_expected_state CHECK (
            ((state = 'APPROVED' OR (state = 'APPLIED' AND approval_request_id IS NOT NULL))
                AND approval_expected_task_version IS NOT NULL AND approval_expected_fence IS NOT NULL
                AND approval_resume_command_id IS NOT NULL)
            OR state = 'SUPERSEDED'
            OR ((state <> 'APPROVED' AND (state <> 'APPLIED' OR approval_request_id IS NULL))
                AND approval_expected_task_version IS NULL AND approval_expected_fence IS NULL
                AND approval_resume_command_id IS NULL)
        );
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_rd_requirement_policy_runs_approval_resume_command
    ON rd_requirement_policy_runs (approval_resume_command_id)
    WHERE approval_resume_command_id IS NOT NULL;

-- Existing installations used a single resource_class. An empty requirements value deliberately
-- retains that legacy fallback while all new commands persist their complete combined token set.
ALTER TABLE rd_requirement_stage_commands
    ADD COLUMN IF NOT EXISTS resource_requirements TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS policy_run_id BIGINT,
    ADD COLUMN IF NOT EXISTS retry_checkpoint_id BIGINT,
    ADD COLUMN IF NOT EXISTS business_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS target_retry_binding_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'fk_rd_requirement_stage_command_policy_run'
                     AND conrelid = 'rd_requirement_stage_commands'::regclass) THEN
        ALTER TABLE rd_requirement_stage_commands
            ADD CONSTRAINT fk_rd_requirement_stage_command_policy_run
                FOREIGN KEY (policy_run_id)
                REFERENCES rd_requirement_policy_runs(id) ON DELETE RESTRICT;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_rd_requirement_stage_commands_claim
    ON rd_requirement_stage_commands (status, next_visible_at, priority_rank, created_at, id);

CREATE INDEX IF NOT EXISTS idx_rd_requirement_stage_commands_lease
    ON rd_requirement_stage_commands (status, lease_until, updated_at);

-- Normal and checkpoint generations intentionally coexist; only lease retries reuse a row.
CREATE UNIQUE INDEX IF NOT EXISTS uk_rd_requirement_stage_commands_normal_identity
    ON rd_requirement_stage_commands (task_id, role, stage)
    WHERE retry_checkpoint_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_rd_requirement_stage_commands_retry_identity
    ON rd_requirement_stage_commands (task_id, role, stage, retry_checkpoint_id)
    WHERE retry_checkpoint_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_rd_requirement_stage_commands_id_checkpoint
    ON rd_requirement_stage_commands (id, retry_checkpoint_id);
DROP INDEX IF EXISTS uk_rd_requirement_stage_commands_identity;

DO $$
BEGIN
    ALTER TABLE rd_requirement_stage_commands
        DROP CONSTRAINT IF EXISTS ck_rd_requirement_stage_command_generation;
    ALTER TABLE rd_requirement_stage_commands
        ADD CONSTRAINT ck_rd_requirement_stage_command_generation CHECK (
            (retry_checkpoint_id IS NULL AND business_generation = 0)
            OR (retry_checkpoint_id IS NOT NULL AND business_generation = retry_checkpoint_id)
        );
END $$;

-- A prepared marker carries the exact task snapshot status that the host transaction must CAS.
-- This lets recovery distinguish a pre-finalizer legacy mutation from a stale command without
-- weakening the version/fencing predicate.
CREATE TABLE IF NOT EXISTS rd_requirement_stage_finalizations (
    command_id              BIGINT NOT NULL REFERENCES rd_requirement_stage_commands(id) ON DELETE CASCADE,
    attempt_no              INTEGER NOT NULL,
    task_id                 BIGINT NOT NULL REFERENCES rd_tasks(id) ON DELETE CASCADE,
    expected_task_version   BIGINT NOT NULL,
    expected_fencing_token  BIGINT NOT NULL,
    expected_task_status    VARCHAR(64) NOT NULL DEFAULT 'CREATED',
    stage                   VARCHAR(128) NOT NULL,
    state                   VARCHAR(32) NOT NULL,
    outcome_status          VARCHAR(64) NOT NULL DEFAULT '',
    result_json             JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message           TEXT NOT NULL DEFAULT '',
    outcome_plan_json       JSONB,
    outcome_plan_digest     VARCHAR(128) NOT NULL DEFAULT '',
    next_command_id         BIGINT,
    prepared_at             TIMESTAMPTZ NOT NULL,
    finalized_at            TIMESTAMPTZ,
    PRIMARY KEY (command_id, attempt_no)
);

ALTER TABLE rd_requirement_stage_finalizations
    ADD COLUMN IF NOT EXISTS expected_task_status VARCHAR(64) NOT NULL DEFAULT 'CREATED';

ALTER TABLE rd_requirement_stage_finalizations
    ADD COLUMN IF NOT EXISTS outcome_plan_json JSONB;

ALTER TABLE rd_requirement_stage_finalizations
    ADD COLUMN IF NOT EXISTS outcome_plan_digest VARCHAR(128) NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_rd_requirement_stage_finalizations_recovery
    ON rd_requirement_stage_finalizations (command_id, state, prepared_at DESC);

-- Host-owned evidence for repository/browser/tool work is intentionally separate from the
-- publication ledger: a tool operation may become externally relevant before PR creation.
CREATE TABLE IF NOT EXISTS rd_provider_tool_operations (
    operation_id  VARCHAR(256) PRIMARY KEY,
    task_id       BIGINT NOT NULL REFERENCES rd_tasks(id) ON DELETE CASCADE,
    stage_run_id  VARCHAR(128) NOT NULL,
    attempt_id    VARCHAR(256) NOT NULL,
    status        VARCHAR(64) NOT NULL,
    reason        TEXT NOT NULL DEFAULT '',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_rd_provider_tool_operation_status
        CHECK (status IN (
            'PREPARED',
            'CLEANLY_ABORTED',
            'UNKNOWN_REMOTE_RESULT',
            'COMMITTED',
            'NEEDS_HUMAN'
        ))
);

CREATE INDEX IF NOT EXISTS idx_rd_provider_tool_operations_task_stage
    ON rd_provider_tool_operations (task_id, stage_run_id, updated_at DESC, operation_id DESC);
