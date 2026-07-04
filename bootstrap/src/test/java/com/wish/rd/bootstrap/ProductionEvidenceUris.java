package com.wish.rd.bootstrap;

import java.net.URI;
import java.util.Set;

/**
 * Production evidence artifacts must be durable references, not local-only paths.
 */
final class ProductionEvidenceUris {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https", "s3", "rd-artifact");

    private ProductionEvidenceUris() {
    }

    static boolean isProductionArtifactUri(String value) {
        String safeValue = value == null ? "" : value.strip();
        if (safeValue.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(safeValue);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!uri.isAbsolute() || !ALLOWED_SCHEMES.contains(scheme)) {
                return false;
            }
            if ("http".equals(scheme) || "https".equals(scheme) || "s3".equals(scheme)) {
                return uri.getHost() != null && !uri.getHost().isBlank();
            }
            String schemeSpecificPart = uri.getSchemeSpecificPart() == null ? "" : uri.getSchemeSpecificPart();
            return !schemeSpecificPart.isBlank();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
