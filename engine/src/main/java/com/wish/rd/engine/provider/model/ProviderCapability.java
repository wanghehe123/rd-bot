package com.wish.rd.engine.provider.model;

/**
 * Declared provider capabilities used for safe routing and fallback decisions.
 */
public enum ProviderCapability {
    TOOL_CALLING,
    STRICT_JSON,
    VISION,
    SIDE_EFFECT_TOOLS
}
