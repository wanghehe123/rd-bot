-- P22: Host Manager decisions, MANAGER_GAP_FIX remediation identity, and coding-source uniqueness.
-- Claim/recoverable predicates that join rd_tasks live in RequirementStageCommandMapper
-- (paused=true is never claimed; WAITING_USER_INPUT only admits USER_ANSWER_RESUME).

CREATE TABLE IF NOT EXISTS rd_task_manager_decisions (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES rd_tasks (id),
    round_no INTEGER NOT NULL,
    source_command_id BIGINT NOT NULL,
    state_version BIGINT NOT NULL,
    state_hash VARCHAR(80) NOT NULL,
    route VARCHAR(32) NOT NULL,
    target_record_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    bounded_contract TEXT NOT NULL DEFAULT '',
    executor_route VARCHAR(64) NOT NULL DEFAULT '',
    rationale TEXT NOT NULL DEFAULT '',
    decision_hash VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_rd_task_manager_decisions_task_round UNIQUE (task_id, round_no),
    CONSTRAINT uk_rd_task_manager_decisions_task_source UNIQUE (task_id, source_command_id),
    CONSTRAINT ck_rd_task_manager_decisions_round CHECK (round_no >= 1),
    CONSTRAINT ck_rd_task_manager_decisions_route CHECK (
        route IN ('EXECUTE', 'DONE', 'BLOCKED', 'ASK', 'REPLAN')
    ),
    CONSTRAINT ck_rd_task_manager_decisions_hash CHECK (decision_hash ~ '^sha256:[0-9a-f]{64}$')
);

CREATE INDEX IF NOT EXISTS idx_rd_task_manager_decisions_task
    ON rd_task_manager_decisions (task_id, round_no DESC);

ALTER TABLE rd_agent_remediation_rounds
    DROP CONSTRAINT IF EXISTS ck_rd_agent_remediation_rounds_kind;
ALTER TABLE rd_agent_remediation_rounds
    ADD CONSTRAINT ck_rd_agent_remediation_rounds_kind CHECK (
        kind IN ('QA_PRODUCT_FIX', 'QA_PROTOCOL_RETRY', 'HOST_VERIFY_FIX', 'MANAGER_GAP_FIX')
    );

ALTER TABLE rd_agent_remediation_rounds
    DROP CONSTRAINT IF EXISTS ck_rd_agent_remediation_rounds_number;
ALTER TABLE rd_agent_remediation_rounds
    ADD CONSTRAINT ck_rd_agent_remediation_rounds_number CHECK (
        (kind = 'QA_PRODUCT_FIX' AND remediation_no BETWEEN 1 AND 2)
        OR (kind = 'QA_PROTOCOL_RETRY' AND remediation_no = 1)
        OR (kind = 'HOST_VERIFY_FIX' AND remediation_no BETWEEN 1 AND 2)
        OR (kind = 'MANAGER_GAP_FIX' AND remediation_no BETWEEN 1 AND 2)
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
        OR (kind = 'MANAGER_GAP_FIX' AND target_coding_stage_run_id IS NOT NULL
            AND target_qa_stage_run_id IS NULL
            AND coding_profile_snapshot_id IS NOT NULL AND qa_profile_snapshot_id IS NULL)
    );

CREATE UNIQUE INDEX IF NOT EXISTS uk_rd_agent_remediation_rounds_coding_source
    ON rd_agent_remediation_rounds (source_stage_run_id)
    WHERE target_coding_stage_run_id IS NOT NULL;

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
            AND remediation_kind IN ('QA_PRODUCT_FIX', 'QA_PROTOCOL_RETRY', 'HOST_VERIFY_FIX', 'MANAGER_GAP_FIX')
            AND remediation_no > 0 AND remediation_source_stage_run_id IS NOT NULL
            AND jsonb_typeof(remediation_request_json) = 'object'
            AND octet_length(remediation_request_json::text) BETWEEN 2 AND 65536
            AND remediation_request_hash ~ '^sha256:[0-9a-f]{64}$'
            AND business_generation = 0)
    );
