-- P14 project-level agent strategy aggregates. Existing P8 per-role profiles remain the dispatch projection.
-- strategy_id is unique per project (composite PK) and capped at 64 so projected
-- profile_id `{projectId}:{strategyId}:{role}` fits VARCHAR(128).
-- If a draft of this file was applied with strategy_id as the sole primary key, drop
-- rd_project_agent_strategy_bindings, rd_agent_strategy_role_slots, and
-- rd_agent_strategy_profiles, then re-run this script.

CREATE TABLE IF NOT EXISTS rd_agent_strategy_profiles (
    project_id   BIGINT NOT NULL,
    strategy_id  VARCHAR(64) NOT NULL,
    name         VARCHAR(128) NOT NULL,
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    version      BIGINT NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, strategy_id),
    CONSTRAINT uk_rd_agent_strategy_profiles_project_name UNIQUE (project_id, name)
);

CREATE INDEX IF NOT EXISTS idx_rd_agent_strategy_profiles_project
    ON rd_agent_strategy_profiles (project_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS rd_agent_strategy_role_slots (
    project_id               BIGINT NOT NULL,
    strategy_id              VARCHAR(64) NOT NULL,
    role                     VARCHAR(64) NOT NULL,
    runtime_type             VARCHAR(32) NOT NULL,
    provider_profile_id      VARCHAR(128) NOT NULL,
    model_override           VARCHAR(256) NOT NULL DEFAULT '',
    extension_set_id         VARCHAR(128) NOT NULL DEFAULT '',
    extension_set_version    BIGINT NOT NULL DEFAULT 0,
    tool_policy_id           VARCHAR(128) NOT NULL,
    tool_policy_version      BIGINT NOT NULL DEFAULT 1,
    image_mode               VARCHAR(32) NOT NULL DEFAULT 'LOCAL_DEFAULT',
    image                    VARCHAR(512) NOT NULL DEFAULT '',
    dockerfile_name          VARCHAR(256) NOT NULL DEFAULT '',
    dockerfile_sha256        VARCHAR(128) NOT NULL DEFAULT '',
    dockerfile_artifact_uri  TEXT NOT NULL DEFAULT '',
    dockerfile_text          TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (project_id, strategy_id, role),
    CONSTRAINT fk_rd_agent_strategy_role_slots_profile
        FOREIGN KEY (project_id, strategy_id)
        REFERENCES rd_agent_strategy_profiles(project_id, strategy_id) ON DELETE CASCADE,
    CONSTRAINT chk_rd_agent_strategy_role CHECK (
        role IN ('REQUIREMENT_REVIEWER', 'SOLUTION_ARCHITECT', 'CODING_AGENT', 'QA_AGENT')
    ),
    CONSTRAINT chk_rd_agent_strategy_runtime CHECK (
        runtime_type IN ('PI', 'CLAUDE_CODE', 'MODEL_ONLY')
    ),
    CONSTRAINT chk_rd_agent_strategy_image_mode CHECK (
        image_mode IN ('LOCAL_DEFAULT', 'CUSTOM')
    )
);

CREATE TABLE IF NOT EXISTS rd_project_agent_strategy_bindings (
    project_id   BIGINT PRIMARY KEY,
    strategy_id  VARCHAR(64) NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_rd_project_agent_strategy_bindings_profile
        FOREIGN KEY (project_id, strategy_id)
        REFERENCES rd_agent_strategy_profiles(project_id, strategy_id)
);
