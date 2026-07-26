package com.wish.rd.exec.repair.pi.model;

import java.nio.file.Path;

/** A publisher-verified, content-addressed extension bundle in the host cache. */
public record VerifiedPiResource(
        String resourceId,
        String version,
        Path cachePath,
        String sha256
) {

    public VerifiedPiResource {
        resourceId = text(resourceId);
        version = text(version);
        cachePath = cachePath == null ? null : cachePath.toAbsolutePath().normalize();
        sha256 = text(sha256).toLowerCase(java.util.Locale.ROOT);
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
