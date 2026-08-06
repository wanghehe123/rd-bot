package com.wish.rd.engine.oracle;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Host-provided evaluation context for assertion runners.
 *
 * @param workspaceRoot clean workspace used for verification replay
 * @param baseUrl       optional service base URL
 * @param attributes    additional typed attributes
 */
public record AssertionEvaluationContext(
        Path workspaceRoot,
        String baseUrl,
        Map<String, String> attributes
) {

    public AssertionEvaluationContext {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
        workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
        attributes = attributes == null || attributes.isEmpty() ? Map.of() : Map.copyOf(attributes);
    }

    public static AssertionEvaluationContext of(Path workspaceRoot) {
        return new AssertionEvaluationContext(workspaceRoot, "", Map.of());
    }
}
