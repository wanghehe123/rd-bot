-- P21: HOST_VERIFY_FIX commands share the remediation generation identity.
-- p20 allowed HOST_VERIFY_FIX on rd_agent_remediation_rounds but left
-- ck_rd_requirement_stage_command_generation_v2 on QA kinds only.

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
            AND remediation_kind IN ('QA_PRODUCT_FIX', 'QA_PROTOCOL_RETRY', 'HOST_VERIFY_FIX')
            AND remediation_no > 0 AND remediation_source_stage_run_id IS NOT NULL
            AND jsonb_typeof(remediation_request_json) = 'object'
            AND octet_length(remediation_request_json::text) BETWEEN 2 AND 65536
            AND remediation_request_hash ~ '^sha256:[0-9a-f]{64}$'
            AND business_generation = 0)
    );
