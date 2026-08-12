package com.wish.rd.exec.repair.oracle.model;

import com.wish.rd.exec.repair.oracle.HostVerifierWorkspaceFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Host-owned replay workspace metadata returned by {@link HostVerifierWorkspaceFactory}.
 * The Bootstrap assertion gate adapts this neutral descriptor to its engine evaluation context.
 *
 * @param workspaceRoot fresh workspace containing only the Host-replayed candidate patch
 * @param baseUrl Host-derived optional validation endpoint
 * @param attributes Host-derived context attributes
 * @param cleanup Host-owned runtime cleanup action
 */
public record HostVerifierWorkspace(
        Path workspaceRoot,
        String baseUrl,
        Map<String, String> attributes,
        AutoCloseable cleanup
) implements AutoCloseable {

    /**
     * Compatibility constructor for workspace-only assertion runners that have no runtime process.
     *
     * @param workspaceRoot fresh workspace root
     * @param baseUrl optional Host-derived base URL
     * @param attributes Host-derived attributes
     */
    public HostVerifierWorkspace(Path workspaceRoot, String baseUrl, Map<String, String> attributes) {
        this(workspaceRoot, baseUrl, attributes, () -> { });
    }

    /** Normalizes the immutable Host-owned workspace descriptor. */
    public HostVerifierWorkspace {
        workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null")
                .toAbsolutePath()
                .normalize();
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
        attributes = attributes == null || attributes.isEmpty() ? Map.of() : Map.copyOf(attributes);
        cleanup = cleanup == null ? () -> { } : cleanup;
    }

    /**
     * Stops the Host-created verifier runtime after assertion evaluation completes.
     *
     * @throws Exception when the runtime cleanup action fails
     */
    @Override
    public void close() throws Exception {
        cleanup.close();
    }
}
