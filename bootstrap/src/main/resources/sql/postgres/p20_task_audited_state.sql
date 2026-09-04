-- P20 Host audited-state writeback. PostgreSQL is the canonical store.

CREATE TABLE IF NOT EXISTS rd_task_audited_state_heads (
    task_id            BIGINT PRIMARY KEY REFERENCES rd_tasks(id) ON DELETE CASCADE,
    state_version      BIGINT NOT NULL CHECK (state_version >= 1),
    state_hash         VARCHAR(71) NOT NULL CHECK (state_hash ~ '^sha256:[0-9a-f]{64}$'),
    last_audit_run_id  VARCHAR(128) NOT NULL DEFAULT '',
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS rd_task_audited_state_revisions (
    task_id       BIGINT NOT NULL REFERENCES rd_tasks(id) ON DELETE CASCADE,
    state_version BIGINT NOT NULL CHECK (state_version >= 1),
    state_hash    VARCHAR(71) NOT NULL CHECK (state_hash ~ '^sha256:[0-9a-f]{64}$'),
    state_json    JSONB NOT NULL,
    audit_run_id  VARCHAR(128) NOT NULL,
    command_id    BIGINT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (task_id, state_version),
    UNIQUE (task_id, state_version)
);

CREATE TABLE IF NOT EXISTS rd_task_audit_runs (
    audit_run_id         VARCHAR(128) PRIMARY KEY,
    task_id              BIGINT NOT NULL REFERENCES rd_tasks(id) ON DELETE CASCADE,
    subject_stage_run_id VARCHAR(128) NOT NULL DEFAULT '',
    subject_role         VARCHAR(64) NOT NULL,
    command_id           BIGINT NOT NULL,
    completion           VARCHAR(32) NOT NULL CHECK (completion IN ('COMPLETE', 'INCOMPLETE', 'BLOCKED')),
    integrity            VARCHAR(32) NOT NULL CHECK (integrity IN ('CLEAN', 'VIOLATION', 'SUSPECT')),
    contract_audit       VARCHAR(32) NOT NULL CHECK (contract_audit IN ('ALIGNED', 'UNKNOWN', 'NEEDS_REVISION', 'INVALID')),
    report_json          JSONB NOT NULL,
    report_hash          VARCHAR(71) NOT NULL CHECK (report_hash ~ '^sha256:[0-9a-f]{64}$'),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (command_id)
);

CREATE INDEX IF NOT EXISTS idx_rd_task_audit_runs_task
    ON rd_task_audit_runs (task_id, created_at, audit_run_id);

CREATE TABLE IF NOT EXISTS rd_task_completion_bindings (
    task_id       BIGINT PRIMARY KEY REFERENCES rd_tasks(id) ON DELETE CASCADE,
    audit_run_id  VARCHAR(128) NOT NULL,
    state_version BIGINT NOT NULL CHECK (state_version >= 1),
    state_hash    VARCHAR(71) NOT NULL CHECK (state_hash ~ '^sha256:[0-9a-f]{64}$'),
    bound_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE rd_agent_remediation_rounds
    DROP CONSTRAINT IF EXISTS ck_rd_agent_remediation_rounds_kind;
ALTER TABLE rd_agent_remediation_rounds
    ADD CONSTRAINT ck_rd_agent_remediation_rounds_kind CHECK (
        kind IN ('QA_PRODUCT_FIX', 'QA_PROTOCOL_RETRY', 'HOST_VERIFY_FIX')
    );

ALTER TABLE rd_agent_remediation_rounds
    DROP CONSTRAINT IF EXISTS ck_rd_agent_remediation_rounds_number;
ALTER TABLE rd_agent_remediation_rounds
    ADD CONSTRAINT ck_rd_agent_remediation_rounds_number CHECK (
        (kind = 'QA_PRODUCT_FIX' AND remediation_no BETWEEN 1 AND 2)
        OR (kind = 'QA_PROTOCOL_RETRY' AND remediation_no = 1)
        OR (kind = 'HOST_VERIFY_FIX' AND remediation_no BETWEEN 1 AND 2)
    );

ALTER TABLE rd_agent_remediation_rounds
    DROP CONSTRAINT IF EXISTS ck_rd_agent_remediation_rounds_targets;
ALTER TABLE rd_agent_remediation_rounds
    ADD CONSTRAINT ck_rd_agent_remediation_rounds_targets CHECK (
        (kind = 'QA_PRODUCT_FIX' AND target_coding_stage_run_id IS NOT NULL
            AND target_qa_stage_run_id IS NOT NULL
            AND coding_profile_snapshot_id IS NOT NULL AND qa_profile_snapshot_id IS NOT NULL)
        OR (kind = 'QA_PROTOCOL_RETRY' AND target_coding_stage_run_id IS NULL
            AND target_qa_stage_run_id IS NOT NULL
            AND coding_profile_snapshot_id IS NULL AND qa_profile_snapshot_id IS NOT NULL)
        OR (kind = 'HOST_VERIFY_FIX' AND target_coding_stage_run_id IS NOT NULL
            AND target_qa_stage_run_id IS NULL
            AND coding_profile_snapshot_id IS NOT NULL AND qa_profile_snapshot_id IS NULL)
    );
