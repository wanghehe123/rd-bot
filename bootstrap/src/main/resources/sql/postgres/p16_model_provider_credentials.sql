-- Provider API keys, stored separately from public routing metadata.
CREATE TABLE IF NOT EXISTS rd_model_provider_credentials (
    provider_id VARCHAR(128) PRIMARY KEY
        REFERENCES rd_model_provider_profiles(provider_id) ON DELETE CASCADE,
    secret      TEXT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_rd_model_provider_credentials_secret CHECK (btrim(secret) <> '')
);
