package com.wish.rd.engine.provider;

import com.wish.rd.engine.agent.model.AgentRole;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Host-side catalog of provider capability profiles used by fallback policy.
 * Unknown providers are treated as generation-only (cannot silently serve tool work).
 */
public final class ProviderCapabilityCatalog {

    private final Map<String, ProviderCapabilityProfile> profiles = new ConcurrentHashMap<>();

    public ProviderCapabilityCatalog() {
        registerDefaults();
    }

    public ProviderCapabilityCatalog register(ProviderCapabilityProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        profiles.put(normalize(profile.providerId()), profile);
        return this;
    }

    public ProviderCapabilityProfile resolve(String providerId) {
        String id = normalize(providerId);
        if (id.isBlank()) {
            id = "unknown";
        }
        ProviderCapabilityProfile known = profiles.get(id);
        if (known != null) {
            return known;
        }
        // Heuristic for unregistered ids: coding/tool-ish names get tool ceiling.
        if (looksLikeToolProvider(id)) {
            return ProviderCapabilityProfile.of(
                    id,
                    ProviderWorkRisk.TOOL_SIDE_EFFECT,
                    ProviderCapability.TOOL_CALLING,
                    ProviderCapability.SIDE_EFFECT_TOOLS,
                    ProviderCapability.STRICT_JSON
            );
        }
        return ProviderCapabilityProfile.of(id, ProviderWorkRisk.GENERATION_ONLY, ProviderCapability.STRICT_JSON);
    }

    public static ProviderWorkRisk workRiskForRole(AgentRole role) {
        if (role == null) {
            return ProviderWorkRisk.GENERATION_ONLY;
        }
        return switch (role) {
            case CODING_AGENT, QA_AGENT, BUG_CODING_AGENT -> ProviderWorkRisk.TOOL_SIDE_EFFECT;
            case REQUIREMENT_REVIEWER, SOLUTION_ARCHITECT,
                 BUG_EVIDENCE_COLLECTOR, BUG_RAG_RETRIEVER, BUG_ACCEPTANCE_PLANNER
                    -> ProviderWorkRisk.GENERATION_ONLY;
        };
    }

    private void registerDefaults() {
        register(ProviderCapabilityProfile.of(
                "openai",
                ProviderWorkRisk.TOOL_SIDE_EFFECT,
                ProviderCapability.TOOL_CALLING,
                ProviderCapability.SIDE_EFFECT_TOOLS,
                ProviderCapability.STRICT_JSON
        ));
        register(ProviderCapabilityProfile.of(
                "anthropic",
                ProviderWorkRisk.TOOL_SIDE_EFFECT,
                ProviderCapability.TOOL_CALLING,
                ProviderCapability.SIDE_EFFECT_TOOLS,
                ProviderCapability.STRICT_JSON
        ));
        register(ProviderCapabilityProfile.of(
                "claude",
                ProviderWorkRisk.TOOL_SIDE_EFFECT,
                ProviderCapability.TOOL_CALLING,
                ProviderCapability.SIDE_EFFECT_TOOLS,
                ProviderCapability.STRICT_JSON
        ));
        register(ProviderCapabilityProfile.of(
                "pi",
                ProviderWorkRisk.TOOL_SIDE_EFFECT,
                ProviderCapability.TOOL_CALLING,
                ProviderCapability.SIDE_EFFECT_TOOLS,
                ProviderCapability.STRICT_JSON
        ));
        register(ProviderCapabilityProfile.of(
                "deepseek",
                ProviderWorkRisk.TOOL_SIDE_EFFECT,
                ProviderCapability.TOOL_CALLING,
                ProviderCapability.SIDE_EFFECT_TOOLS,
                ProviderCapability.STRICT_JSON
        ));
        register(ProviderCapabilityProfile.of(
                "weak-gen",
                ProviderWorkRisk.GENERATION_ONLY,
                ProviderCapability.STRICT_JSON
        ));
    }

    private static boolean looksLikeToolProvider(String id) {
        return id.contains("claude")
                || id.contains("openai")
                || id.contains("gpt")
                || id.contains("pi")
                || id.contains("coding")
                || id.contains("anthropic")
                || id.contains("deepseek");
    }

    private static String normalize(String providerId) {
        return providerId == null ? "" : providerId.strip().toLowerCase(Locale.ROOT);
    }
}
