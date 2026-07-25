CREATE TABLE IF NOT EXISTS rd_project_runtime_profiles (
    project_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    agent_type VARCHAR(32) NOT NULL,
    image TEXT NOT NULL,
    dockerfile_uri TEXT NOT NULL,
    dockerfile_sha256 VARCHAR(64) NOT NULL,
    dockerfile_name TEXT NOT NULL,
    validation_status VARCHAR(16) NOT NULL,
    validation_summary TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, role),
    CONSTRAINT chk_rd_project_runtime_role CHECK (
        role IN ('REQUIREMENT_REVIEWER', 'SOLUTION_ARCHITECT', 'CODING_AGENT', 'QA_AGENT')
    ),
    CONSTRAINT chk_rd_project_runtime_agent_type CHECK (agent_type = 'CLAUDE_CODE'),
    CONSTRAINT chk_rd_project_runtime_validation CHECK (validation_status = 'VERIFIED')
);

CREATE INDEX IF NOT EXISTS idx_rd_project_runtime_profiles_updated
    ON rd_project_runtime_profiles (project_id, updated_at DESC);
