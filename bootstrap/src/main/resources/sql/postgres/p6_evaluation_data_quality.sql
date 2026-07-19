-- Scoped workflow-experience metadata required by the evaluation-quality repair.
-- This migration is idempotent and intentionally leaves historical scope empty.

ALTER TABLE rd_experience_entries
    ADD COLUMN IF NOT EXISTS project_id VARCHAR(128) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS repository_fingerprint VARCHAR(512) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS intent_id VARCHAR(128) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS tags_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS source_revision VARCHAR(256) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS evidence_quality DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS applicable_roles_json JSONB NOT NULL DEFAULT '[]'::jsonb;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_rd_experience_entries_evidence_quality'
    ) THEN
        ALTER TABLE rd_experience_entries
            ADD CONSTRAINT ck_rd_experience_entries_evidence_quality
            CHECK (evidence_quality >= 0.0 AND evidence_quality <= 1.0);
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_rd_experience_entries_scope
    ON rd_experience_entries (project_id, repository_fingerprint, reusable, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_rd_experience_entries_applicable_roles
    ON rd_experience_entries USING GIN (applicable_roles_json);
