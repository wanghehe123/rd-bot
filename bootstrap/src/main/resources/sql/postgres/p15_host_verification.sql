ALTER TABLE rd_qa_validation_profiles
    ADD COLUMN IF NOT EXISTS build_commands_json JSONB,
    ADD COLUMN IF NOT EXISTS static_commands_json JSONB;
