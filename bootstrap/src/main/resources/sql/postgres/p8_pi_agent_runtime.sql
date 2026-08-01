-- P8 Pi Agent runtime control plane. Existing P7 rows remain Claude image profiles.

CREATE TABLE IF NOT EXISTS rd_model_provider_profiles (
    provider_id                    VARCHAR(128) PRIMARY KEY,
    display_name                   VARCHAR(256) NOT NULL,
    protocol                       VARCHAR(64) NOT NULL,
    base_url                       TEXT NOT NULL,
    model_id                       VARCHAR(256) NOT NULL,
    credential_environment_variable VARCHAR(128) NOT NULL,
    auth_header                    BOOLEAN NOT NULL DEFAULT FALSE,
    enabled                        BOOLEAN NOT NULL DEFAULT FALSE,
    version                        BIGINT NOT NULL DEFAULT 1,
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS rd_agent_execution_profiles (
    profile_id             VARCHAR(128) PRIMARY KEY,
    project_id             BIGINT NOT NULL,
    role                   VARCHAR(64) NOT NULL,
    name                   VARCHAR(128) NOT NULL,
    runtime_type           VARCHAR(32) NOT NULL,
    provider_profile_id    VARCHAR(128) NOT NULL,
    model_override         VARCHAR(256) NOT NULL DEFAULT '',
    runtime_image_role     VARCHAR(64) NOT NULL DEFAULT '',
    extension_set_id       VARCHAR(128) NOT NULL DEFAULT '',
    extension_set_version  BIGINT,
    tool_policy_id         VARCHAR(128) NOT NULL,
    tool_policy_version    BIGINT NOT NULL DEFAULT 1,
    session_policy         VARCHAR(64) NOT NULL DEFAULT 'ARCHIVE_NO_RESUME',
    enabled                BOOLEAN NOT NULL DEFAULT FALSE,
    version                BIGINT NOT NULL DEFAULT 1,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_rd_agent_execution_profiles_project_role_name
        UNIQUE (project_id, role, name),
    CONSTRAINT chk_rd_agent_execution_profiles_role CHECK (
        role IN ('REQUIREMENT_REVIEWER', 'SOLUTION_ARCHITECT', 'CODING_AGENT', 'QA_AGENT')
    ),
    CONSTRAINT chk_rd_agent_execution_profiles_runtime CHECK (
        runtime_type IN ('PI', 'CLAUDE_CODE', 'MODEL_ONLY')
    ),
    CONSTRAINT chk_rd_agent_execution_profiles_session CHECK (
        session_policy IN ('ARCHIVE_NO_RESUME')
    ),
    CONSTRAINT uk_rd_agent_execution_profiles_project_role_id
        UNIQUE (profile_id, project_id, role)
);

CREATE INDEX IF NOT EXISTS idx_rd_agent_execution_profiles_project_role
    ON rd_agent_execution_profiles (project_id, role, enabled, updated_at DESC);

CREATE TABLE IF NOT EXISTS rd_project_agent_profile_bindings (
    project_id     BIGINT NOT NULL,
    role           VARCHAR(64) NOT NULL,
    profile_id     VARCHAR(128) NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, role),
    CONSTRAINT chk_rd_project_agent_profile_bindings_role CHECK (
        role IN ('REQUIREMENT_REVIEWER', 'SOLUTION_ARCHITECT', 'CODING_AGENT', 'QA_AGENT')
    ),
    FOREIGN KEY (profile_id, project_id, role)
        REFERENCES rd_agent_execution_profiles(profile_id, project_id, role)
);

CREATE TABLE IF NOT EXISTS rd_task_agent_profile_overrides (
    task_id        BIGINT NOT NULL,
    project_id     BIGINT NOT NULL,
    role           VARCHAR(64) NOT NULL,
    profile_id     VARCHAR(128) NOT NULL,
    created_by     VARCHAR(128) NOT NULL DEFAULT '',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (task_id, role),
    CONSTRAINT chk_rd_task_agent_profile_overrides_role CHECK (
        role IN ('REQUIREMENT_REVIEWER', 'SOLUTION_ARCHITECT', 'CODING_AGENT', 'QA_AGENT')
    ),
    FOREIGN KEY (profile_id, project_id, role)
        REFERENCES rd_agent_execution_profiles(profile_id, project_id, role)
);

CREATE INDEX IF NOT EXISTS idx_rd_task_agent_profile_overrides_project
    ON rd_task_agent_profile_overrides (project_id, task_id);

CREATE TABLE IF NOT EXISTS rd_agent_execution_profile_snapshots (
    snapshot_id             VARCHAR(128) PRIMARY KEY,
    stage_run_id            BIGINT NOT NULL UNIQUE REFERENCES rd_agent_stage_runs(id) ON DELETE CASCADE,
    task_id                 BIGINT NOT NULL REFERENCES rd_tasks(id) ON DELETE CASCADE,
    role                    VARCHAR(64) NOT NULL,
    attempt_no              INTEGER NOT NULL,
    runtime_type            VARCHAR(32) NOT NULL,
    snapshot_json           TEXT NOT NULL,
    snapshot_hash           VARCHAR(128) NOT NULL,
    resolved_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_rd_agent_execution_profile_snapshots_attempt CHECK (attempt_no > 0),
    CONSTRAINT chk_rd_agent_execution_profile_snapshots_runtime CHECK (
        runtime_type IN ('PI', 'CLAUDE_CODE', 'MODEL_ONLY')
    )
);

CREATE INDEX IF NOT EXISTS idx_rd_agent_execution_profile_snapshots_task
    ON rd_agent_execution_profile_snapshots (task_id, role, resolved_at DESC);

CREATE TABLE IF NOT EXISTS rd_agent_tool_policies (
    policy_id       VARCHAR(128) NOT NULL,
    version         BIGINT NOT NULL DEFAULT 1,
    policy_json     TEXT NOT NULL,
    policy_hash     VARCHAR(128) NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (policy_id, version)
);

CREATE TABLE IF NOT EXISTS rd_agent_extensions (
    extension_id    VARCHAR(128) PRIMARY KEY,
    display_name    VARCHAR(256) NOT NULL,
    owner_type      VARCHAR(32) NOT NULL DEFAULT 'PLATFORM_ADMIN',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_rd_agent_extensions_owner CHECK (owner_type = 'PLATFORM_ADMIN')
);

CREATE TABLE IF NOT EXISTS rd_agent_extension_versions (
    extension_id          VARCHAR(128) NOT NULL REFERENCES rd_agent_extensions(extension_id) ON DELETE CASCADE,
    version               VARCHAR(128) NOT NULL,
    artifact_uri          TEXT NOT NULL,
    artifact_sha256       VARCHAR(128) NOT NULL,
    signature             TEXT NOT NULL,
    manifest_json         JSONB NOT NULL,
    checksum_json         JSONB NOT NULL,
    sbom_json             JSONB NOT NULL DEFAULT '{}'::jsonb,
    pi_version_range      VARCHAR(256) NOT NULL,
    bridge_protocol_range VARCHAR(256) NOT NULL,
    target_os             VARCHAR(64) NOT NULL,
    target_arch           VARCHAR(64) NOT NULL,
    node_abi              VARCHAR(64) NOT NULL,
    status                VARCHAR(32) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (extension_id, version),
    CONSTRAINT chk_rd_agent_extension_versions_status CHECK (
        status IN ('UPLOADED', 'VERIFYING', 'VERIFIED', 'REJECTED', 'REVOKED')
    )
);

CREATE TABLE IF NOT EXISTS rd_agent_extension_sets (
    extension_set_id  VARCHAR(128) NOT NULL,
    version            BIGINT NOT NULL,
    set_hash           VARCHAR(128) NOT NULL,
    enabled            BOOLEAN NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (extension_set_id, version)
);

CREATE TABLE IF NOT EXISTS rd_agent_extension_set_members (
    extension_set_id  VARCHAR(128) NOT NULL,
    set_version       BIGINT NOT NULL,
    extension_id      VARCHAR(128) NOT NULL,
    extension_version VARCHAR(128) NOT NULL,
    enabled            BOOLEAN NOT NULL DEFAULT TRUE,
    config_hash        VARCHAR(128) NOT NULL DEFAULT '',
    PRIMARY KEY (extension_set_id, set_version, extension_id),
    FOREIGN KEY (extension_set_id, set_version)
        REFERENCES rd_agent_extension_sets(extension_set_id, version),
    FOREIGN KEY (extension_id, extension_version)
        REFERENCES rd_agent_extension_versions(extension_id, version)
);

-- Restricted Pi raw/session object index. Content remains in RustFS; this table
-- only enables ownership checks and expiry cleanup.
CREATE TABLE IF NOT EXISTS rd_agent_private_artifacts (
    artifact_id            VARCHAR(256) PRIMARY KEY,
    task_id                BIGINT NOT NULL REFERENCES rd_tasks(id) ON DELETE CASCADE,
    stage_run_id           BIGINT NOT NULL REFERENCES rd_agent_stage_runs(id) ON DELETE CASCADE,
    snapshot_id            VARCHAR(128) NOT NULL,
    artifact_type          VARCHAR(64) NOT NULL,
    artifact_name          VARCHAR(512) NOT NULL,
    artifact_uri           TEXT NOT NULL,
    bytes                  BIGINT NOT NULL,
    sha256                 VARCHAR(128) NOT NULL,
    content_type           VARCHAR(128) NOT NULL,
    retention_class        VARCHAR(64) NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at             TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_rd_agent_private_artifacts_bytes CHECK (bytes > 0),
    CONSTRAINT chk_rd_agent_private_artifacts_uri CHECK (artifact_uri LIKE 's3://%')
);

CREATE INDEX IF NOT EXISTS idx_rd_agent_private_artifacts_expiry
    ON rd_agent_private_artifacts (expires_at);

CREATE INDEX IF NOT EXISTS idx_rd_agent_private_artifacts_stage
    ON rd_agent_private_artifacts (task_id, stage_run_id, created_at DESC);

-- Default immutable tool policies (coding vs read-only QA).
INSERT INTO rd_agent_tool_policies (policy_id, version, policy_json, policy_hash, enabled)
VALUES (
    'legacy-host-bound',
    1,
    '{"hostAllow":["bash","edit","rd_record_fact","rd_submit_result","rd_todo_rewrite","rd_todo_update_status","read","write"],"allow":["bash","edit","rd_record_fact","rd_submit_result","rd_todo_rewrite","rd_todo_update_status","read","write"],"deny":[]}',
    'legacy-host-bound-v1',
    TRUE
)
ON CONFLICT (policy_id, version) DO NOTHING;

INSERT INTO rd_agent_tool_policies (policy_id, version, policy_json, policy_hash, enabled)
VALUES (
    'default-qa',
    1,
    '{"hostAllow":["bash","rd_submit_result","read"],"allow":["bash","rd_submit_result","read"],"deny":["edit","write"]}',
    'default-qa-v1',
    TRUE
)
ON CONFLICT (policy_id, version) DO NOTHING;
