package com.wish.rd.engine.oracle;

import com.wish.rd.engine.oracle.model.AssertionSpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Canonical content hashing so Host can reject agent-mutated assertion bundles.
 */
public final class AssertionSpecHasher {

    private AssertionSpecHasher() {
    }

    /**
     * Digests one assertion using a stable, order-sensitive field encoding.
     *
     * @param spec assertion
     * @return {@code sha256:} hex digest
     */
    public static String hash(AssertionSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        return digest(encode(spec));
    }

    /**
     * Digests a frozen bundle. Specs are sorted by id so list order cannot mask mutation.
     *
     * @param specs assertion list
     * @return {@code sha256:} hex digest
     */
    public static String hashBundle(List<AssertionSpec> specs) {
        List<AssertionSpec> ordered = normalize(specs);
        String payload = ordered.stream()
                .map(AssertionSpecHasher::encode)
                .collect(Collectors.joining("\n"));
        return digest(payload);
    }

    private static List<AssertionSpec> normalize(List<AssertionSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        return specs.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(AssertionSpec::id))
                .toList();
    }

    private static String encode(AssertionSpec spec) {
        return String.join("|",
                field(spec.id()),
                field(spec.sourceCriteriaId()),
                join(spec.preconditions()),
                field(spec.fixture()),
                field(spec.action()),
                spec.assertionType().name(),
                field(spec.target()),
                field(spec.operator()),
                field(spec.expected()),
                field(spec.tolerance()),
                join(spec.evidenceRequired()),
                Long.toString(spec.timeoutMillis()),
                field(spec.sensitivity())
        );
    }

    private static String join(List<String> values) {
        return values.stream().map(AssertionSpecHasher::field).collect(Collectors.joining(","));
    }

    private static String field(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace(",", "\\,");
    }

    private static String digest(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
