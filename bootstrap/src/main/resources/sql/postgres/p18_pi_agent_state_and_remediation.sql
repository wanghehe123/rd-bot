-- P18 PI Agent state projection and remediation control-plane schema.

ALTER TABLE rd_agent_execution_profiles
    ADD COLUMN IF NOT EXISTS capabilities_json JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE TABLE IF NOT EXISTS rd_agent_stage_state_latest (
    stage_run_id              BIGINT PRIMARY KEY REFERENCES rd_agent_stage_runs(id) ON DELETE CASCADE,
    task_id                   BIGINT NOT NULL REFERENCES rd_tasks(id) ON DELETE CASCADE,
    role                      VARCHAR(64) NOT NULL,
    attempt_no                INTEGER NOT NULL,
    state_sequence            BIGINT NOT NULL DEFAULT -1,
    state_hash                VARCHAR(71) NOT NULL DEFAULT '',
    state_json                TEXT NOT NULL DEFAULT '{}',
    state_projected_at        TIMESTAMPTZ,
    injection_sequence        BIGINT NOT NULL DEFAULT 0,
    injected_state_sequence   BIGINT NOT NULL DEFAULT -1,
    injected_state_hash       VARCHAR(71) NOT NULL DEFAULT '',
    prompt_hash               VARCHAR(71) NOT NULL DEFAULT '',
    injected_block_hash       VARCHAR(71) NOT NULL DEFAULT '',
    injected_block            TEXT NOT NULL DEFAULT '',
    injection_idempotency_key VARCHAR(71) NOT NULL DEFAULT '',
    injected_at               TIMESTAMPTZ,
    finalized                 BOOLEAN NOT NULL DEFAULT FALSE,
    finalized_at              TIMESTAMPTZ,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_rd_agent_stage_state_latest_role CHECK (
        role IN ('REQUIREMENT_REVIEWER', 'SOLUTION_ARCHITECT', 'CODING_AGENT', 'QA_AGENT')
    ),
    CONSTRAINT chk_rd_agent_stage_state_latest_attempt CHECK (attempt_no > 0),
    CONSTRAINT chk_rd_agent_stage_state_latest_state_sequence CHECK (state_sequence >= -1),
    CONSTRAINT chk_rd_agent_stage_state_latest_injection_sequence CHECK (injection_sequence >= 0),
    CONSTRAINT chk_rd_agent_stage_state_latest_injected_state_sequence CHECK (injected_state_sequence >= -1),
    CONSTRAINT chk_rd_agent_stage_state_latest_state_payload CHECK (
        (state_sequence = -1 AND state_hash = '' AND state_json = '{}')
        OR (state_sequence >= 0 AND state_hash ~ '^sha256:[0-9a-f]{64}$'
            AND jsonb_typeof(state_json::jsonb) = 'object')
    ),
    CONSTRAINT chk_rd_agent_stage_state_latest_injection_payload CHECK (
        (injection_sequence = 0 AND injected_state_sequence = -1
            AND injected_state_hash = '' AND prompt_hash = '' AND injected_block_hash = ''
            AND injected_block = '' AND injection_idempotency_key = '' AND injected_at IS NULL)
        OR (injection_sequence > 0 AND injected_state_sequence >= 0
            AND injected_state_hash ~ '^sha256:[0-9a-f]{64}$'
            AND prompt_hash ~ '^sha256:[0-9a-f]{64}$'
            AND injected_block_hash ~ '^sha256:[0-9a-f]{64}$'
            AND injection_idempotency_key ~ '^sha256:[0-9a-f]{64}$'
            AND octet_length(injected_block) BETWEEN 1 AND 65536 AND injected_at IS NOT NULL)
    ),
    CONSTRAINT chk_rd_agent_stage_state_latest_finalized CHECK (
        (finalized = FALSE AND finalized_at IS NULL) OR (finalized = TRUE AND finalized_at IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_rd_agent_stage_state_latest_task_role
    ON rd_agent_stage_state_latest (task_id, role, updated_at DESC);

-- Durable PI remediation truth. A source QA stage can claim at most one round of each kind,
-- while the task/kind/no identity bounds product repair to two rounds and protocol retry to one.
CREATE TABLE IF NOT EXISTS rd_agent_remediation_rounds (
    id                          BIGINT PRIMARY KEY,
    task_id                     BIGINT NOT NULL REFERENCES rd_tasks(id) ON DELETE CASCADE,
    kind                        VARCHAR(32) NOT NULL,
    remediation_no              INTEGER NOT NULL,
    source_stage_run_id          BIGINT NOT NULL REFERENCES rd_agent_stage_runs(id) ON DELETE RESTRICT,
    source_command_id            BIGINT NOT NULL REFERENCES rd_requirement_stage_commands(id) ON DELETE RESTRICT,
    source_result_hash           VARCHAR(71) NOT NULL,
    source_task_version          BIGINT NOT NULL,
    source_fencing_token         BIGINT NOT NULL,
    target_coding_stage_run_id   BIGINT REFERENCES rd_agent_stage_runs(id) ON DELETE RESTRICT,
    target_qa_stage_run_id       BIGINT REFERENCES rd_agent_stage_runs(id) ON DELETE RESTRICT,
    first_command_id             BIGINT REFERENCES rd_requirement_stage_commands(id) ON DELETE RESTRICT,
    coding_profile_snapshot_id   VARCHAR(128) REFERENCES rd_agent_execution_profile_snapshots(snapshot_id)
                                  ON DELETE RESTRICT,
    qa_profile_snapshot_id       VARCHAR(128) REFERENCES rd_agent_execution_profile_snapshots(snapshot_id)
                                  ON DELETE RESTRICT,
    request_json                 JSONB NOT NULL,
    request_hash                 VARCHAR(71) NOT NULL,
    status                       VARCHAR(32) NOT NULL DEFAULT 'PLANNED',
    row_version                  BIGINT NOT NULL DEFAULT 0,
    lease_owner                  VARCHAR(256) NOT NULL DEFAULT '',
    lease_until                  TIMESTAMPTZ,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at                   TIMESTAMPTZ,
    dispatched_at                TIMESTAMPTZ,
    completed_at                 TIMESTAMPTZ,
    CONSTRAINT ck_rd_agent_remediation_rounds_kind CHECK (
        kind IN ('QA_PRODUCT_FIX', 'QA_PROTOCOL_RETRY')
    ),
    CONSTRAINT ck_rd_agent_remediation_rounds_number CHECK (
        (kind = 'QA_PRODUCT_FIX' AND remediation_no BETWEEN 1 AND 2)
        OR (kind = 'QA_PROTOCOL_RETRY' AND remediation_no = 1)
    ),
    CONSTRAINT ck_rd_agent_remediation_rounds_status CHECK (
        status IN ('PLANNED', 'CLAIMED', 'DISPATCHED', 'COMPLETED', 'FAILED_NEEDS_HUMAN')
    ),
    CONSTRAINT ck_rd_agent_remediation_rounds_source CHECK (
        source_task_version >= 0 AND source_fencing_token > 0
        AND source_result_hash ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_rd_agent_remediation_rounds_request CHECK (
        jsonb_typeof(request_json) = 'object'
        AND octet_length(request_json::text) BETWEEN 2 AND 65536
        AND request_hash ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_rd_agent_remediation_rounds_version CHECK (row_version >= 0),
    CONSTRAINT ck_rd_agent_remediation_rounds_lease CHECK (
        (lease_owner = '' AND lease_until IS NULL) OR (lease_owner <> '' AND lease_until IS NOT NULL)
    ),
    CONSTRAINT ck_rd_agent_remediation_rounds_targets CHECK (
        (kind = 'QA_PRODUCT_FIX' AND target_coding_stage_run_id IS NOT NULL
            AND target_qa_stage_run_id IS NOT NULL
            AND coding_profile_snapshot_id IS NOT NULL AND qa_profile_snapshot_id IS NOT NULL)
        OR (kind = 'QA_PROTOCOL_RETRY' AND target_coding_stage_run_id IS NULL
            AND target_qa_stage_run_id IS NOT NULL
            AND coding_profile_snapshot_id IS NULL AND qa_profile_snapshot_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_rd_agent_remediation_rounds_task_kind_no
    ON rd_agent_remediation_rounds (task_id, kind, remediation_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_rd_agent_remediation_rounds_source_kind
    ON rd_agent_remediation_rounds (source_stage_run_id, kind);
CREATE UNIQUE INDEX IF NOT EXISTS uk_rd_agent_remediation_rounds_first_command
    ON rd_agent_remediation_rounds (first_command_id)
    WHERE first_command_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_rd_agent_remediation_rounds_recovery
    ON rd_agent_remediation_rounds (status, lease_until, updated_at, id);

-- A command belongs to exactly one business generation: normal, retry checkpoint, or PI
-- remediation. The remediation identity is copied onto Coding and QA continuation commands.
ALTER TABLE rd_requirement_stage_commands
    ADD COLUMN IF NOT EXISTS remediation_round_id BIGINT,
    ADD COLUMN IF NOT EXISTS remediation_kind VARCHAR(32),
    ADD COLUMN IF NOT EXISTS remediation_no INTEGER,
    ADD COLUMN IF NOT EXISTS remediation_source_stage_run_id BIGINT,
    ADD COLUMN IF NOT EXISTS remediation_request_json JSONB,
    ADD COLUMN IF NOT EXISTS remediation_request_hash VARCHAR(71);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'fk_rd_requirement_stage_command_remediation_round'
                     AND conrelid = 'rd_requirement_stage_commands'::regclass) THEN
        ALTER TABLE rd_requirement_stage_commands
            ADD CONSTRAINT fk_rd_requirement_stage_command_remediation_round
                FOREIGN KEY (remediation_round_id)
                REFERENCES rd_agent_remediation_rounds(id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'fk_rd_requirement_stage_command_remediation_source'
                     AND conrelid = 'rd_requirement_stage_commands'::regclass) THEN
        ALTER TABLE rd_requirement_stage_commands
            ADD CONSTRAINT fk_rd_requirement_stage_command_remediation_source
                FOREIGN KEY (remediation_source_stage_run_id)
                REFERENCES rd_agent_stage_runs(id) ON DELETE RESTRICT;
    END IF;

    ALTER TABLE rd_requirement_stage_commands
        DROP CONSTRAINT IF EXISTS ck_rd_requirement_stage_command_generation_v2;
    ALTER TABLE rd_requirement_stage_commands
        ADD CONSTRAINT ck_rd_requirement_stage_command_generation_v2 CHECK (
            (retry_checkpoint_id IS NULL AND remediation_round_id IS NULL
                AND remediation_kind IS NULL AND remediation_no IS NULL
                AND remediation_source_stage_run_id IS NULL AND remediation_request_json IS NULL
                AND remediation_request_hash IS NULL AND business_generation = 0)
            OR (retry_checkpoint_id IS NOT NULL AND remediation_round_id IS NULL
                AND remediation_kind IS NULL AND remediation_no IS NULL
                AND remediation_source_stage_run_id IS NULL AND remediation_request_json IS NULL
                AND remediation_request_hash IS NULL
                AND business_generation = retry_checkpoint_id)
            OR (retry_checkpoint_id IS NULL AND remediation_round_id IS NOT NULL
                AND remediation_kind IN ('QA_PRODUCT_FIX', 'QA_PROTOCOL_RETRY')
                AND remediation_no > 0 AND remediation_source_stage_run_id IS NOT NULL
                AND jsonb_typeof(remediation_request_json) = 'object'
                AND octet_length(remediation_request_json::text) BETWEEN 2 AND 65536
                AND remediation_request_hash ~ '^sha256:[0-9a-f]{64}$'
                AND business_generation = 0)
        );
END $$;

-- The p1 normal identity predicate also matched remediation rows. Replace it with three
-- disjoint partial identities so replay converges without blocking a new remediation attempt.
DROP INDEX IF EXISTS uk_rd_requirement_stage_commands_normal_identity;
CREATE UNIQUE INDEX uk_rd_requirement_stage_commands_normal_identity
    ON rd_requirement_stage_commands (task_id, role, stage)
    WHERE retry_checkpoint_id IS NULL AND remediation_round_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_rd_requirement_stage_commands_remediation_identity
    ON rd_requirement_stage_commands (task_id, role, stage, remediation_round_id)
    WHERE retry_checkpoint_id IS NULL AND remediation_round_id IS NOT NULL;
