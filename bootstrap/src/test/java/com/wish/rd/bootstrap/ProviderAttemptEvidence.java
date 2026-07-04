package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Provider attempt evidence helper for production smoke tests.
 */
final class ProviderAttemptEvidence {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ProviderAttemptEvidence() {
    }

    static int count(String providerAttemptsJson) {
        String safeJson = providerAttemptsJson == null ? "" : providerAttemptsJson.strip();
        if (safeJson.isBlank()) {
            return 0;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(safeJson);
            return root.isArray() ? root.size() : 0;
        } catch (IOException exception) {
            return 0;
        }
    }

    static FallbackEvidence fallback(String providerAttemptsJson) {
        String safeJson = providerAttemptsJson == null ? "" : providerAttemptsJson.strip();
        if (safeJson.isBlank()) {
            return FallbackEvidence.empty();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(safeJson);
            if (!root.isArray() || root.size() < 2) {
                return FallbackEvidence.empty();
            }
            String failedProvider = "";
            String failedStatus = "";
            for (JsonNode attempt : root) {
                String provider = firstText(attempt, "provider", "providerName", "name");
                String status = firstText(attempt, "status", "result", "state");
                if (isSuccess(status)) {
                    if (!failedProvider.isBlank()
                            && !provider.isBlank()
                            && !sameProvider(failedProvider, provider)) {
                        return new FallbackEvidence(true, failedProvider, failedStatus, provider);
                    }
                } else if (!status.isBlank() && failedProvider.isBlank()) {
                    failedProvider = provider;
                    failedStatus = status;
                }
            }
            return FallbackEvidence.empty();
        } catch (IOException exception) {
            return FallbackEvidence.empty();
        }
    }

    private static boolean isSuccess(String status) {
        return "SUCCESS".equalsIgnoreCase(status) || "SUCCEEDED".equalsIgnoreCase(status);
    }

    private static boolean sameProvider(String left, String right) {
        return left.strip().equalsIgnoreCase(right.strip());
    }

    private static String firstText(JsonNode node, String... names) {
        for (String name : names) {
            String value = node.path(name).asText("").strip();
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    record FallbackEvidence(
            boolean detected,
            String failedProvider,
            String failedStatus,
            String activeProvider
    ) {
        FallbackEvidence {
            failedProvider = failedProvider == null ? "" : failedProvider.strip();
            failedStatus = failedStatus == null ? "" : failedStatus.strip();
            activeProvider = activeProvider == null ? "" : activeProvider.strip();
        }

        static FallbackEvidence empty() {
            return new FallbackEvidence(false, "", "", "");
        }
    }
}
