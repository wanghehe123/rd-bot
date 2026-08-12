package com.wish.rd.engine.provider.model;

/**
 * Risk class of the work that may receive a provider fallback.
 */
public enum ProviderWorkRisk {
    /** Pure generation / summarization with no tools. */
    GENERATION_ONLY,
    /** Tool use that can mutate workspace or call external APIs. */
    TOOL_SIDE_EFFECT,
    /** Migrations, security, permission, or irreversible ops. */
    HIGH_RISK
}
