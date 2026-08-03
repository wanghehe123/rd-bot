-- P10 Skill Hub catalog and role bindings (persistence readiness).

CREATE TABLE IF NOT EXISTS rd_skill_catalog (
    skill_id       VARCHAR(256) PRIMARY KEY,
    version        VARCHAR(128) NOT NULL DEFAULT '',
    description    TEXT NOT NULL DEFAULT '',
    guide_prompt   TEXT NOT NULL DEFAULT '',
    force_guide    BOOLEAN NOT NULL DEFAULT FALSE,
    risk_level     VARCHAR(32) NOT NULL DEFAULT 'LOW',
    checksum       VARCHAR(128) NOT NULL DEFAULT '',
    source_uri     TEXT NOT NULL DEFAULT '',
    install_path   TEXT NOT NULL DEFAULT '',
    status         VARCHAR(32) NOT NULL DEFAULT 'DISABLED',
    allowed_roles_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_rd_skill_catalog_status
        CHECK (status IN ('ACTIVE', 'WAITING_APPROVAL', 'DISABLED', 'REJECTED')),
    CONSTRAINT ck_rd_skill_catalog_risk
        CHECK (risk_level IN ('UNKNOWN', 'LOW', 'MEDIUM', 'HIGH'))
);

CREATE INDEX IF NOT EXISTS idx_rd_skill_catalog_status
    ON rd_skill_catalog (status, updated_at DESC);

CREATE TABLE IF NOT EXISTS rd_skill_role_bindings (
    role           VARCHAR(64) NOT NULL,
    skill_id       VARCHAR(256) NOT NULL REFERENCES rd_skill_catalog(skill_id) ON DELETE CASCADE,
    sort_order     INTEGER NOT NULL DEFAULT 0,
    force_guide    BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (role, skill_id)
);

CREATE INDEX IF NOT EXISTS idx_rd_skill_role_bindings_role
    ON rd_skill_role_bindings (role, sort_order);
