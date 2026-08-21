CREATE TABLE IF NOT EXISTS rd_task_retry_checkpoints (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    failure_phase VARCHAR(48) NOT NULL,
    retry_from_role VARCHAR(64) NOT NULL DEFAULT '',
    failed_stage_run_id BIGINT,
    failed_retrieval_run_id BIGINT,
    failed_ai_review_run_id BIGINT,
    attempt_no INTEGER NOT NULL,
    idempotency_key VARCHAR(512) NOT NULL,
    source_task_status VARCHAR(48) NOT NULL,
    source_task_version BIGINT NOT NULL,
    operator_note TEXT NOT NULL DEFAULT '',
    evidence_material_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(32) NOT NULL,
    reason TEXT NOT NULL DEFAULT '',
    error_message TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_task_retry_checkpoint_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uk_task_retry_checkpoint_attempt UNIQUE (task_id, attempt_no)
);

ALTER TABLE rd_task_retry_checkpoints
    ADD COLUMN IF NOT EXISTS operator_note TEXT NOT NULL DEFAULT '';
ALTER TABLE rd_task_retry_checkpoints
    ADD COLUMN IF NOT EXISTS evidence_material_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX IF NOT EXISTS idx_task_retry_checkpoint_task
    ON rd_task_retry_checkpoints (task_id, attempt_no, created_at);
CREATE INDEX IF NOT EXISTS idx_task_retry_checkpoint_active
    ON rd_task_retry_checkpoints (task_id, status, updated_at)
    WHERE status IN ('CREATED', 'DISPATCHED');

CREATE TABLE IF NOT EXISTS rd_ai_review_runs (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    attempt_no INTEGER NOT NULL,
    parent_run_id BIGINT,
    status VARCHAR(40) NOT NULL,
    model_name VARCHAR(256) NOT NULL DEFAULT '',
    package_hash VARCHAR(128) NOT NULL DEFAULT '',
    decision VARCHAR(32) NOT NULL DEFAULT '',
    score INTEGER NOT NULL DEFAULT 0,
    retry_from_role VARCHAR(64) NOT NULL DEFAULT '',
    summary TEXT NOT NULL DEFAULT '',
    error_category VARCHAR(128) NOT NULL DEFAULT '',
    error_message TEXT NOT NULL DEFAULT '',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_ai_review_run_attempt UNIQUE (task_id, attempt_no),
    CONSTRAINT ck_ai_review_run_score CHECK (score BETWEEN 0 AND 100)
);

CREATE INDEX IF NOT EXISTS idx_ai_review_run_task
    ON rd_ai_review_runs (task_id, attempt_no, created_at);
CREATE INDEX IF NOT EXISTS idx_ai_review_run_status
    ON rd_ai_review_runs (status, updated_at);

CREATE TABLE IF NOT EXISTS rd_ai_review_events (
    id BIGINT PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES rd_ai_review_runs(id) ON DELETE CASCADE,
    from_status VARCHAR(40) NOT NULL,
    to_status VARCHAR(40) NOT NULL,
    trigger VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
    message TEXT NOT NULL DEFAULT '',
    error_category VARCHAR(128) NOT NULL DEFAULT '',
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ai_review_event_run
    ON rd_ai_review_events (run_id, occurred_at, id);

CREATE TABLE IF NOT EXISTS rd_ai_review_artifacts (
    id BIGINT PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES rd_ai_review_runs(id) ON DELETE CASCADE,
    artifact_type VARCHAR(64) NOT NULL,
    artifact_uri TEXT NOT NULL DEFAULT '',
    content_preview TEXT NOT NULL DEFAULT '',
    content_hash VARCHAR(128) NOT NULL DEFAULT '',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    redacted BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ai_review_artifact_run
    ON rd_ai_review_artifacts (run_id, created_at, id);

-- Checkpoint-bound retry generations were added after the original P3 control plane.  Keep
-- legacy rows audit-only (fence/generation zero) rather than fabricating authorization data.
ALTER TABLE rd_task_retry_checkpoints
    ADD COLUMN IF NOT EXISTS source_fencing_token BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS failed_stage_command_id BIGINT,
    ADD COLUMN IF NOT EXISTS failed_stage VARCHAR(128) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS source_policy_run_id BIGINT,
    ADD COLUMN IF NOT EXISTS policy_run_id BIGINT,
    ADD COLUMN IF NOT EXISTS source_plan_digest VARCHAR(128) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS authorization_plan_digest VARCHAR(128) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS publication_operation_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS dispatch_task_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS dispatch_fencing_token BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS dispatch_command_id BIGINT,
    ADD COLUMN IF NOT EXISTS business_generation BIGINT NOT NULL DEFAULT 0;

-- Rows migrated from the old schema are deliberately not execution-authorizable.  New rows
-- carry both a real source fence and their own durable checkpoint id as generation identity.
DO $$
BEGIN
    UPDATE rd_task_retry_checkpoints SET publication_operation_id = NULL
     WHERE publication_operation_id = '';
    ALTER TABLE rd_task_retry_checkpoints ALTER COLUMN publication_operation_id DROP NOT NULL;
    ALTER TABLE rd_task_retry_checkpoints ALTER COLUMN publication_operation_id DROP DEFAULT;
    ALTER TABLE rd_task_retry_checkpoints
        DROP CONSTRAINT IF EXISTS ck_rd_task_retry_checkpoint_generation;
    ALTER TABLE rd_task_retry_checkpoints
        ADD CONSTRAINT ck_rd_task_retry_checkpoint_generation CHECK (
            (source_fencing_token = 0 AND business_generation = 0)
            OR (source_fencing_token > 0 AND business_generation = id)
        );
    ALTER TABLE rd_task_retry_checkpoints
        DROP CONSTRAINT IF EXISTS ck_rd_task_retry_checkpoint_failed_command_stage;
    ALTER TABLE rd_task_retry_checkpoints
        ADD CONSTRAINT ck_rd_task_retry_checkpoint_failed_command_stage CHECK (
            (failed_stage_command_id IS NULL AND failed_stage = '')
            OR (failed_stage_command_id IS NOT NULL AND failed_stage <> '')
        );
    ALTER TABLE rd_task_retry_checkpoints
        DROP CONSTRAINT IF EXISTS ck_rd_task_retry_checkpoint_verified_identity;
    ALTER TABLE rd_task_retry_checkpoints
        ADD CONSTRAINT ck_rd_task_retry_checkpoint_verified_identity CHECK (
            source_fencing_token = 0
            OR (source_fencing_token > 0 AND failed_stage_command_id IS NOT NULL AND failed_stage <> '')
        );
    ALTER TABLE rd_task_retry_checkpoints
        DROP CONSTRAINT IF EXISTS ck_rd_task_retry_checkpoint_dispatch_pair;
    ALTER TABLE rd_task_retry_checkpoints
        ADD CONSTRAINT ck_rd_task_retry_checkpoint_dispatch_pair CHECK (
            (dispatch_task_version = 0 AND dispatch_fencing_token = 0 AND dispatch_command_id IS NULL)
            OR (dispatch_task_version > 0 AND dispatch_fencing_token > 0 AND dispatch_command_id IS NOT NULL)
        );
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_rd_task_retry_checkpoint_task'
                   AND conrelid = 'rd_task_retry_checkpoints'::regclass) THEN
        ALTER TABLE rd_task_retry_checkpoints ADD CONSTRAINT fk_rd_task_retry_checkpoint_task
            FOREIGN KEY (task_id) REFERENCES rd_tasks(id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_rd_task_retry_checkpoint_failed_command'
                   AND conrelid = 'rd_task_retry_checkpoints'::regclass) THEN
        ALTER TABLE rd_task_retry_checkpoints ADD CONSTRAINT fk_rd_task_retry_checkpoint_failed_command
            FOREIGN KEY (failed_stage_command_id) REFERENCES rd_requirement_stage_commands(id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_rd_task_retry_checkpoint_source_policy'
                   AND conrelid = 'rd_task_retry_checkpoints'::regclass) THEN
        ALTER TABLE rd_task_retry_checkpoints ADD CONSTRAINT fk_rd_task_retry_checkpoint_source_policy
            FOREIGN KEY (source_policy_run_id) REFERENCES rd_requirement_policy_runs(id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_rd_task_retry_checkpoint_policy'
                   AND conrelid = 'rd_task_retry_checkpoints'::regclass) THEN
        ALTER TABLE rd_task_retry_checkpoints ADD CONSTRAINT fk_rd_task_retry_checkpoint_policy
            FOREIGN KEY (policy_run_id) REFERENCES rd_requirement_policy_runs(id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_rd_task_retry_checkpoint_publication'
                   AND conrelid = 'rd_task_retry_checkpoints'::regclass) THEN
        ALTER TABLE rd_task_retry_checkpoints ADD CONSTRAINT fk_rd_task_retry_checkpoint_publication
            FOREIGN KEY (publication_operation_id) REFERENCES rd_requirement_publications(operation_id) ON DELETE RESTRICT;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM rd_task_retry_checkpoints
         WHERE status IN ('CREATED', 'DISPATCHED', 'WAITING_APPROVAL')
         GROUP BY task_id HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'cannot create active retry checkpoint uniqueness: duplicate active task rows exist';
    END IF;
END $$;
DROP INDEX IF EXISTS idx_task_retry_checkpoint_active;
CREATE UNIQUE INDEX IF NOT EXISTS uk_rd_task_retry_checkpoint_one_active
    ON rd_task_retry_checkpoints (task_id)
    WHERE status IN ('CREATED', 'DISPATCHED', 'WAITING_APPROVAL');

-- A binding is a typed target identity, never a polymorphic foreign key.  The composite parent
-- FK and the command target FK below reject accidental cross-checkpoint lineage joins.
CREATE TABLE IF NOT EXISTS rd_task_retry_attempt_bindings (
    id BIGINT PRIMARY KEY,
    checkpoint_id BIGINT NOT NULL REFERENCES rd_task_retry_checkpoints(id) ON DELETE RESTRICT,
    binding_kind VARCHAR(32) NOT NULL,
    role VARCHAR(64) NOT NULL DEFAULT '',
    stage_run_id BIGINT,
    retrieval_run_id BIGINT,
    ai_review_run_id BIGINT,
    parent_binding_id BIGINT,
    attempt_no INTEGER NOT NULL CHECK (attempt_no > 0),
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_rd_task_retry_attempt_binding_exact_target CHECK (
        (binding_kind = 'AGENT_STAGE' AND stage_run_id IS NOT NULL AND retrieval_run_id IS NULL AND ai_review_run_id IS NULL)
        OR (binding_kind = 'RETRIEVAL' AND stage_run_id IS NULL AND retrieval_run_id IS NOT NULL AND ai_review_run_id IS NULL)
        OR (binding_kind = 'AI_REVIEW' AND stage_run_id IS NULL AND retrieval_run_id IS NULL AND ai_review_run_id IS NOT NULL)
    ),
    CONSTRAINT ck_rd_task_retry_attempt_binding_parent_kind CHECK (
        (binding_kind = 'AGENT_STAGE' AND parent_binding_id IS NULL)
        OR (binding_kind = 'RETRIEVAL' AND parent_binding_id IS NOT NULL)
        OR binding_kind = 'AI_REVIEW'
    ),
    CONSTRAINT fk_rd_task_retry_attempt_binding_stage FOREIGN KEY (stage_run_id)
        REFERENCES rd_agent_stage_runs(id) ON DELETE RESTRICT,
    CONSTRAINT fk_rd_task_retry_attempt_binding_retrieval FOREIGN KEY (retrieval_run_id)
        REFERENCES rd_rag_retrieval_runs(id) ON DELETE RESTRICT,
    CONSTRAINT fk_rd_task_retry_attempt_binding_ai_review FOREIGN KEY (ai_review_run_id)
        REFERENCES rd_ai_review_runs(id) ON DELETE RESTRICT,
    CONSTRAINT uk_rd_task_retry_attempt_binding_id_checkpoint UNIQUE (id, checkpoint_id),
    CONSTRAINT fk_rd_task_retry_attempt_binding_parent_checkpoint
        FOREIGN KEY (parent_binding_id, checkpoint_id)
        REFERENCES rd_task_retry_attempt_bindings(id, checkpoint_id) ON DELETE RESTRICT
);

DO $$
BEGIN
    ALTER TABLE rd_task_retry_attempt_bindings
        DROP CONSTRAINT IF EXISTS ck_rd_task_retry_attempt_binding_parent_kind;
    ALTER TABLE rd_task_retry_attempt_bindings
        ADD CONSTRAINT ck_rd_task_retry_attempt_binding_parent_kind CHECK (
            (binding_kind = 'AGENT_STAGE' AND parent_binding_id IS NULL)
            OR (binding_kind = 'RETRIEVAL' AND parent_binding_id IS NOT NULL)
            OR binding_kind = 'AI_REVIEW'
        );
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_rd_task_retry_attempt_binding_stage_target
    ON rd_task_retry_attempt_bindings (stage_run_id) WHERE stage_run_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_rd_task_retry_attempt_binding_retrieval_target
    ON rd_task_retry_attempt_bindings (retrieval_run_id) WHERE retrieval_run_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_rd_task_retry_attempt_binding_ai_target
    ON rd_task_retry_attempt_bindings (ai_review_run_id) WHERE ai_review_run_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_rd_task_retry_attempt_binding_top_slot
    ON rd_task_retry_attempt_bindings (checkpoint_id, binding_kind, role, ordinal)
    WHERE parent_binding_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_rd_task_retry_attempt_binding_child_slot
    ON rd_task_retry_attempt_bindings (checkpoint_id, parent_binding_id, binding_kind, role, ordinal)
    WHERE parent_binding_id IS NOT NULL;

CREATE OR REPLACE FUNCTION rd_validate_task_retry_attempt_binding_scope()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    checkpoint_task BIGINT;
    target_task BIGINT;
    target_role VARCHAR(64);
    target_attempt_no INTEGER;
    target_stage_run_id BIGINT;
    parent_kind VARCHAR(32);
    parent_role VARCHAR(64);
    parent_stage_run_id BIGINT;
BEGIN
    SELECT task_id INTO checkpoint_task FROM rd_task_retry_checkpoints WHERE id = NEW.checkpoint_id;
    IF NEW.binding_kind = 'AGENT_STAGE' THEN
        SELECT task_id, role, attempt_no INTO target_task, target_role, target_attempt_no
          FROM rd_agent_stage_runs WHERE id = NEW.stage_run_id;
    ELSIF NEW.binding_kind = 'RETRIEVAL' THEN
        SELECT task_id, role, attempt_no, stage_run_id
          INTO target_task, target_role, target_attempt_no, target_stage_run_id
          FROM rd_rag_retrieval_runs WHERE id = NEW.retrieval_run_id;
    ELSE
        SELECT task_id INTO target_task FROM rd_ai_review_runs WHERE id = NEW.ai_review_run_id;
    END IF;
    IF checkpoint_task IS NULL OR target_task IS DISTINCT FROM checkpoint_task THEN
        RAISE EXCEPTION 'retry attempt binding target must belong to checkpoint task';
    END IF;
    IF NEW.binding_kind IN ('AGENT_STAGE', 'RETRIEVAL')
       AND (target_role IS DISTINCT FROM NEW.role OR target_attempt_no IS DISTINCT FROM NEW.attempt_no) THEN
        RAISE EXCEPTION 'retry attempt binding target role/attempt must match binding';
    END IF;
    IF NEW.parent_binding_id IS NOT NULL THEN
        SELECT binding_kind, role, stage_run_id INTO parent_kind, parent_role, parent_stage_run_id
          FROM rd_task_retry_attempt_bindings WHERE id = NEW.parent_binding_id AND checkpoint_id = NEW.checkpoint_id;
        IF parent_kind IS DISTINCT FROM 'AGENT_STAGE' OR NEW.role IS DISTINCT FROM parent_role THEN
            RAISE EXCEPTION 'retry child binding must retain same-checkpoint agent-stage parent and role';
        END IF;
        IF NEW.binding_kind = 'RETRIEVAL' AND target_stage_run_id IS DISTINCT FROM parent_stage_run_id THEN
            RAISE EXCEPTION 'retry retrieval binding must reference its parent agent-stage run';
        END IF;
    END IF;
    RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS tr_rd_task_retry_attempt_binding_scope ON rd_task_retry_attempt_bindings;
CREATE TRIGGER tr_rd_task_retry_attempt_binding_scope
    BEFORE INSERT OR UPDATE ON rd_task_retry_attempt_bindings
    FOR EACH ROW EXECUTE FUNCTION rd_validate_task_retry_attempt_binding_scope();

-- Commands need their direct checkpoint FK even when the stage is infrastructure/policy and has
-- no attempt target.  Composite keys make both dispatched-command and target-binding lineage exact.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_rd_requirement_stage_command_checkpoint'
                   AND conrelid = 'rd_requirement_stage_commands'::regclass) THEN
        ALTER TABLE rd_requirement_stage_commands ADD CONSTRAINT fk_rd_requirement_stage_command_checkpoint
            FOREIGN KEY (retry_checkpoint_id) REFERENCES rd_task_retry_checkpoints(id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_rd_requirement_stage_command_binding_checkpoint'
                   AND conrelid = 'rd_requirement_stage_commands'::regclass) THEN
        ALTER TABLE rd_requirement_stage_commands ADD CONSTRAINT fk_rd_requirement_stage_command_binding_checkpoint
            FOREIGN KEY (target_retry_binding_id, retry_checkpoint_id)
            REFERENCES rd_task_retry_attempt_bindings(id, checkpoint_id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_rd_task_retry_checkpoint_dispatch_command'
                   AND conrelid = 'rd_task_retry_checkpoints'::regclass) THEN
        ALTER TABLE rd_task_retry_checkpoints ADD CONSTRAINT fk_rd_task_retry_checkpoint_dispatch_command
            FOREIGN KEY (dispatch_command_id, id)
            REFERENCES rd_requirement_stage_commands(id, retry_checkpoint_id) ON DELETE RESTRICT;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS rd_task_failure_provenance (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES rd_tasks(id) ON DELETE RESTRICT,
    failed_stage_command_id BIGINT NOT NULL REFERENCES rd_requirement_stage_commands(id) ON DELETE RESTRICT,
    failed_command_attempt_no INTEGER NOT NULL CHECK (failed_command_attempt_no >= 0),
    failed_stage VARCHAR(128) NOT NULL,
    failure_phase VARCHAR(48) NOT NULL,
    outcome_status VARCHAR(64) NOT NULL,
    failed_task_version BIGINT NOT NULL CHECK (failed_task_version >= 0),
    failed_task_fencing_token BIGINT NOT NULL CHECK (failed_task_fencing_token > 0),
    failed_stage_run_id BIGINT REFERENCES rd_agent_stage_runs(id) ON DELETE RESTRICT,
    failed_retrieval_run_id BIGINT REFERENCES rd_rag_retrieval_runs(id) ON DELETE RESTRICT,
    failed_ai_review_run_id BIGINT REFERENCES rd_ai_review_runs(id) ON DELETE RESTRICT,
    source_policy_run_id BIGINT REFERENCES rd_requirement_policy_runs(id) ON DELETE RESTRICT,
    source_plan_digest VARCHAR(128) NOT NULL DEFAULT '',
    publication_operation_id VARCHAR(128) REFERENCES rd_requirement_publications(operation_id) ON DELETE RESTRICT,
    failure_kind VARCHAR(128) NOT NULL DEFAULT '',
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_rd_task_failure_provenance_task_snapshot
        UNIQUE (task_id, failed_task_version, failed_task_fencing_token),
    CONSTRAINT uk_rd_task_failure_provenance_command_attempt
        UNIQUE (failed_stage_command_id, failed_command_attempt_no)
);

-- Low-range sequence for technical-exhaustion backfill provenance IDs. Snowflake IDs here are
-- ~7.5e18, so values starting at 1 cannot collide with runtime-issued identities.
CREATE SEQUENCE IF NOT EXISTS rd_task_failure_provenance_backfill_id_seq START WITH 1 INCREMENT BY 1;

-- Repair tasks stranded by non-atomic technical exhaustion: DEAD_LETTERED checkpoint-bound
-- command, terminal task snapshot without matching provenance, and exactly one candidate command.
-- Never rewrites rd_tasks version/fence. Fail closed on ambiguity (two+ candidates => skip).
INSERT INTO rd_task_failure_provenance (
    id, task_id, failed_stage_command_id, failed_command_attempt_no, failed_stage,
    failure_phase, outcome_status, failed_task_version, failed_task_fencing_token,
    failed_stage_run_id, failed_retrieval_run_id, failed_ai_review_run_id, source_policy_run_id,
    source_plan_digest, publication_operation_id, failure_kind, recorded_at
)
SELECT nextval('rd_task_failure_provenance_backfill_id_seq'),
       candidate.task_id,
       candidate.command_id,
       candidate.attempt_no,
       candidate.failed_stage,
       candidate.failure_phase,
       candidate.outcome_status,
       candidate.failed_task_version,
       candidate.failed_task_fencing_token,
       candidate.failed_stage_run_id,
       candidate.failed_retrieval_run_id,
       candidate.failed_ai_review_run_id,
       candidate.source_policy_run_id,
       candidate.source_plan_digest,
       candidate.publication_operation_id,
       'TECHNICAL_EXHAUSTION_BACKFILL',
       now()
  FROM (
        SELECT t.id AS task_id,
               c.id AS command_id,
               c.attempt_no,
               cp.failed_stage,
               cp.failure_phase,
               t.status AS outcome_status,
               t.version AS failed_task_version,
               t.fencing_token AS failed_task_fencing_token,
               CASE
                   WHEN b.binding_kind = 'AGENT_STAGE' THEN b.stage_run_id
                   ELSE cp.failed_stage_run_id
               END AS failed_stage_run_id,
               CASE
                   WHEN b.binding_kind = 'RETRIEVAL' THEN b.retrieval_run_id
                   ELSE cp.failed_retrieval_run_id
               END AS failed_retrieval_run_id,
               CASE
                   WHEN b.binding_kind = 'AI_REVIEW' THEN b.ai_review_run_id
                   ELSE cp.failed_ai_review_run_id
               END AS failed_ai_review_run_id,
               cp.source_policy_run_id,
               COALESCE(cp.source_plan_digest, '') AS source_plan_digest,
               cp.publication_operation_id,
               COUNT(*) OVER (PARTITION BY t.id, t.version, t.fencing_token) AS candidate_count
          FROM rd_tasks t
          JOIN rd_requirement_stage_commands c
            ON c.task_id = t.id
           AND c.retry_checkpoint_id IS NOT NULL
           AND c.status = 'DEAD_LETTERED'
          JOIN rd_task_retry_checkpoints cp
            ON cp.id = c.retry_checkpoint_id
           AND COALESCE(BTRIM(cp.failed_stage), '') <> ''
           AND COALESCE(BTRIM(cp.failure_phase), '') <> ''
          LEFT JOIN rd_task_retry_attempt_bindings b
            ON b.id = c.target_retry_binding_id
           AND b.checkpoint_id = c.retry_checkpoint_id
         WHERE t.status IN ('FAILED_RETRYABLE', 'DEAD_LETTERED')
           AND t.fencing_token > 0
           AND NOT EXISTS (
                SELECT 1
                  FROM rd_task_failure_provenance p
                 WHERE p.task_id = t.id
                   AND p.failed_task_version = t.version
                   AND p.failed_task_fencing_token = t.fencing_token
           )
  ) candidate
 WHERE candidate.candidate_count = 1
ON CONFLICT DO NOTHING;

-- Backfilled provenance carries checkpoint policy; stranded pre-fix retry commands often left
-- policy_run_id NULL. Restore the authorization identity so current and relaxed corroboration both
-- accept the row without rewriting task version/fence.
UPDATE rd_requirement_stage_commands AS command
   SET policy_run_id = checkpoint.source_policy_run_id,
       updated_at = now()
  FROM rd_task_retry_checkpoints AS checkpoint,
       rd_task_failure_provenance AS provenance
 WHERE command.retry_checkpoint_id = checkpoint.id
   AND provenance.failed_stage_command_id = command.id
   AND provenance.failed_command_attempt_no = command.attempt_no
   AND provenance.failure_kind = 'TECHNICAL_EXHAUSTION_BACKFILL'
   AND command.policy_run_id IS NULL
   AND checkpoint.source_policy_run_id IS NOT NULL
   AND command.status = 'DEAD_LETTERED';

-- Ensure a recoverable PREPARED marker exists for the exhausted attempt. Dispatch may have reused an
-- earlier attempt marker via command identity; authoritative findExact still needs a corroborating
-- marker on or before the exhausted attempt_no (runtime exhaustCommand now inserts one too).
INSERT INTO rd_requirement_stage_finalizations (
    command_id, attempt_no, task_id, expected_task_version, expected_fencing_token, expected_task_status,
    stage, state, outcome_status, result_json, error_message, outcome_plan_json, outcome_plan_digest,
    next_command_id, prepared_at, finalized_at
)
SELECT command.id,
       command.attempt_no,
       command.task_id,
       command.task_version,
       command.fencing_token,
       'RECOVERING',
       command.stage,
       'PREPARED',
       '',
       '{}'::jsonb,
       '',
       NULL,
       '',
       NULL,
       now(),
       NULL
  FROM rd_task_failure_provenance provenance
  JOIN rd_requirement_stage_commands command
    ON command.id = provenance.failed_stage_command_id
   AND command.attempt_no = provenance.failed_command_attempt_no
 WHERE provenance.failure_kind = 'TECHNICAL_EXHAUSTION_BACKFILL'
   AND command.status = 'DEAD_LETTERED'
   AND command.stage NOT IN ('POLICY_EVALUATE', 'POLICY_APPLY', 'APPROVAL_RESUME')
   AND NOT EXISTS (
        SELECT 1
          FROM rd_requirement_stage_finalizations marker
         WHERE marker.command_id = command.id
           AND marker.attempt_no <= command.attempt_no
           AND marker.state IN ('PREPARED', 'OUTCOME_RECORDED')
   )
ON CONFLICT (command_id, attempt_no) DO NOTHING;

-- CAS-cancel stranded PENDING attempts bound to already-terminal checkpoints so backfilled
-- provenance can resolve the same way the runtime exhaustCommand path does.
UPDATE rd_agent_stage_runs stage_run
   SET status = 'CANCELLED',
       error_category = COALESCE(NULLIF(BTRIM(stage_run.error_category), ''), 'RETRY_CHECKPOINT_EXHAUSTED'),
       error_message = COALESCE(
           NULLIF(BTRIM(stage_run.error_message), ''),
           'unused pending attempt cancelled with terminal checkpoint during technical exhaustion backfill'),
       updated_at = now(),
       finished_at = COALESCE(stage_run.finished_at, now())
  FROM rd_task_retry_attempt_bindings binding
  JOIN rd_task_retry_checkpoints checkpoint
    ON checkpoint.id = binding.checkpoint_id
 WHERE stage_run.id = binding.stage_run_id
   AND binding.binding_kind = 'AGENT_STAGE'
   AND checkpoint.status IN ('SUCCEEDED', 'FAILED_RETRYABLE', 'FAILED_NEEDS_HUMAN', 'CANCELLED')
   AND stage_run.status = 'PENDING';

-- Backfill missing PR_PUBLICATION provenance for terminal publication failures whose command
-- stage is bare PUBLICATION or canonical PUBLICATION:<operationId>. Only tasks with exactly one
-- publication ledger row are eligible so operation identity stays fail-closed.
INSERT INTO rd_task_failure_provenance (
    id, task_id, failed_stage_command_id, failed_command_attempt_no, failed_stage, failure_phase,
    outcome_status, failed_task_version, failed_task_fencing_token, failed_stage_run_id,
    failed_retrieval_run_id, failed_ai_review_run_id, source_policy_run_id, source_plan_digest,
    publication_operation_id, failure_kind, recorded_at
)
SELECT nextval('rd_task_failure_provenance_backfill_id_seq'),
       candidate.task_id,
       candidate.command_id,
       candidate.attempt_no,
       'PUBLICATION:' || candidate.operation_id,
       'PR_PUBLICATION',
       candidate.outcome_status,
       candidate.failed_task_version,
       candidate.failed_task_fencing_token,
       NULL,
       NULL,
       NULL,
       candidate.source_policy_run_id,
       candidate.source_plan_digest,
       candidate.operation_id,
       candidate.failure_kind,
       now()
  FROM (
        SELECT t.id AS task_id,
               c.id AS command_id,
               c.attempt_no,
               pub.operation_id,
               t.status AS outcome_status,
               t.version AS failed_task_version,
               t.fencing_token AS failed_task_fencing_token,
               c.policy_run_id AS source_policy_run_id,
               COALESCE(policy.plan_digest, '') AS source_plan_digest,
               COALESCE(NULLIF(BTRIM(pub.status), ''), 'UNKNOWN_REMOTE_RESULT') AS failure_kind,
               COUNT(*) OVER (PARTITION BY t.id, t.version, t.fencing_token) AS candidate_count,
               COUNT(*) OVER (PARTITION BY t.id) AS publication_count
          FROM rd_tasks t
          JOIN rd_requirement_stage_commands c
            ON c.task_id = t.id
           AND c.status IN ('DEAD_LETTERED', 'FAILED')
           AND (
                c.stage = 'PUBLICATION'
                OR c.stage LIKE 'PUBLICATION:%'
           )
          JOIN rd_requirement_publications pub
            ON pub.task_id = t.id
          LEFT JOIN rd_requirement_policy_runs policy
            ON policy.id = c.policy_run_id
           AND policy.task_id = t.id
         WHERE t.status IN ('FAILED_RETRYABLE', 'FAILED_NEEDS_HUMAN')
           AND t.fencing_token > 0
           AND c.policy_run_id IS NOT NULL
           AND COALESCE(policy.plan_digest, '') <> ''
           AND NOT EXISTS (
                SELECT 1
                  FROM rd_task_failure_provenance p
                 WHERE p.task_id = t.id
                   AND p.failed_task_version = t.version
                   AND p.failed_task_fencing_token = t.fencing_token
           )
  ) candidate
 WHERE candidate.candidate_count = 1
   AND candidate.publication_count = 1
ON CONFLICT DO NOTHING;
