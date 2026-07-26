package com.wish.rd.bootstrap.controller.admin.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Separate fail-closed capability gate for profile/binding mutations. */
@Component
public final class AgentRuntimeMutationAccessPolicy {

    public static final String HEADER_NAME = "X-RD-Agent-Runtime-Token";

    private final byte[] configuredToken;

    public AgentRuntimeMutationAccessPolicy(
            @Value("${rd.executor.agent-runtime.mutation-token:}") String token
    ) {
        configuredToken = (token == null ? "" : token.strip()).getBytes(StandardCharsets.UTF_8);
    }

    public void requireAuthorized(String suppliedToken) {
        if (configuredToken.length == 0) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "agent runtime mutations are disabled until RD_AGENT_RUNTIME_MUTATION_TOKEN is configured"
            );
        }
        byte[] supplied = (suppliedToken == null ? "" : suppliedToken)
                .getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(configuredToken, supplied)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "agent runtime mutation token is invalid");
        }
    }
}
