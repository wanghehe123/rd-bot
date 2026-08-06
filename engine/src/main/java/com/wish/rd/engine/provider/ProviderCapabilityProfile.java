package com.wish.rd.engine.provider;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable capability snapshot for one provider profile candidate.
 *
 * @param providerId   provider id
 * @param capabilities declared capabilities
 * @param riskCeiling  highest work risk this provider may serve
 */
public record ProviderCapabilityProfile(
        String providerId,
        Set<ProviderCapability> capabilities,
        ProviderWorkRisk riskCeiling
) {

    public ProviderCapabilityProfile {
        providerId = providerId == null ? "" : providerId.strip();
        if (providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        capabilities = capabilities == null || capabilities.isEmpty()
                ? Set.of()
                : Set.copyOf(capabilities);
        riskCeiling = riskCeiling == null ? ProviderWorkRisk.GENERATION_ONLY : riskCeiling;
    }

    public boolean supports(ProviderCapability capability) {
        return capability != null && capabilities.contains(capability);
    }

    public boolean mayServe(ProviderWorkRisk risk) {
        if (risk == null) {
            return false;
        }
        return risk.ordinal() <= riskCeiling.ordinal();
    }

    public static ProviderCapabilityProfile of(
            String providerId,
            ProviderWorkRisk riskCeiling,
            ProviderCapability... capabilities
    ) {
        EnumSet<ProviderCapability> set = EnumSet.noneOf(ProviderCapability.class);
        if (capabilities != null) {
            for (ProviderCapability capability : capabilities) {
                if (capability != null) {
                    set.add(capability);
                }
            }
        }
        return new ProviderCapabilityProfile(providerId, set, riskCeiling);
    }

    public static ProviderFailureClass parseFailure(String raw) {
        String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "429", "RATE_LIMIT" -> ProviderFailureClass.RATE_LIMIT;
            case "5XX", "500", "502", "503", "504", "SERVER_ERROR" -> ProviderFailureClass.SERVER_ERROR;
            case "TIMEOUT" -> ProviderFailureClass.TIMEOUT;
            case "AUTH", "401", "403" -> ProviderFailureClass.AUTH;
            // Provider output failed host/schema validation; generation roles may try another profile.
            case "FAILED_VALIDATION", "VALIDATION" -> ProviderFailureClass.SERVER_ERROR;
            default -> ProviderFailureClass.UNKNOWN;
        };
    }

    public enum ProviderFailureClass {
        RATE_LIMIT,
        SERVER_ERROR,
        TIMEOUT,
        AUTH,
        UNKNOWN
    }
}
