package com.wish.rd.bootstrap.controller.admin.project;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Narrow capability gate for the only admin mutation that builds user-supplied Dockerfiles and can later run with
 * provider credentials. It deliberately fails closed until an operator configures a separate one-time token.
 */
@Component
public final class ProjectRuntimeProfileMutationAccessPolicy {

    public static final String HEADER_NAME = "X-RD-Runtime-Profile-Token";

    private final byte[] configuredToken;

    public ProjectRuntimeProfileMutationAccessPolicy(
            @Value("${rd.executor.docker.runtime-profiles.upload-token:}") String uploadToken
    ) {
        String normalized = uploadToken == null ? "" : uploadToken.strip();
        this.configuredToken = normalized.getBytes(StandardCharsets.UTF_8);
    }

    public void requireAuthorized(String suppliedToken) {
        if (configuredToken.length == 0) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "runtime profile mutations are disabled until RD_RUNTIME_PROFILE_UPLOAD_TOKEN is configured"
            );
        }
        byte[] supplied = (suppliedToken == null ? "" : suppliedToken).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(configuredToken, supplied)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "runtime profile mutation token is invalid");
        }
    }
}
