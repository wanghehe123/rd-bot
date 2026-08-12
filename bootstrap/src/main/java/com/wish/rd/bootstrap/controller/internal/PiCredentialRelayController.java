package com.wish.rd.bootstrap.controller.internal;

import com.wish.rd.bootstrap.executor.PiCredentialRelayService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Internal Host request proxy for Pi provider traffic. It accepts only an opaque
 * lease from the task-local relay sidecar and never serializes a provider secret.
 */
@RestController
@ConditionalOnProperty(
        prefix = "rd.executor.pi",
        name = "credential-relay-enabled",
        havingValue = "true"
)
public class PiCredentialRelayController {

    private static final String PATH = "/internal/pi/credential-relay/proxy";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String TASK_HEADER = "X-RD-Pi-Relay-Task-Id";
    private static final String STAGE_HEADER = "X-RD-Pi-Relay-Stage-Run-Id";
    private static final String PROVIDER_HEADER = "X-RD-Pi-Relay-Provider-Id";
    private static final String METHOD_HEADER = "X-RD-Pi-Relay-Method";
    private static final String PATH_HEADER = "X-RD-Pi-Relay-Path";

    private final PiCredentialRelayService relayService;

    /**
     * Creates the internal Host proxy endpoint.
     *
     * @param relayService Host-owned lease authorization and provider forwarding service
     */
    public PiCredentialRelayController(PiCredentialRelayService relayService) {
        if (relayService == null) {
            throw new IllegalArgumentException("relayService must not be null");
        }
        this.relayService = relayService;
    }

    /**
     * Proxies one provider request from the trusted task-local sidecar.
     *
     * @param authorization opaque Pi lease bearer token
     * @param taskId task identity injected by the sidecar
     * @param stageRunId stage identity injected by the sidecar
     * @param providerId provider identity injected by the sidecar
     * @param method original Pi provider method
     * @param path original Pi provider path
     * @param headers incoming provider headers
     * @param body bounded provider request body
     * @return provider response without a Host credential
     */
    @PostMapping(value = PATH, consumes = MediaType.ALL_VALUE)
    public ResponseEntity<byte[]> proxy(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = TASK_HEADER, required = false) String taskId,
            @RequestHeader(value = STAGE_HEADER, required = false) String stageRunId,
            @RequestHeader(value = PROVIDER_HEADER, required = false) String providerId,
            @RequestHeader(value = METHOD_HEADER, required = false) String method,
            @RequestHeader(value = PATH_HEADER, required = false) String path,
            @RequestHeader Map<String, String> headers,
            @RequestBody(required = false) byte[] body
    ) {
        PiCredentialRelayService.ProxyResponse response = relayService.proxy(
                bearerToken(authorization),
                taskId,
                stageRunId,
                providerId,
                method,
                path,
                headers,
                body
        );
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.status())
                .cacheControl(CacheControl.noStore());
        response.headers().forEach(builder::header);
        return builder.body(response.body());
    }

    private static String bearerToken(String authorization) {
        if (authorization == null
                || authorization.length() <= BEARER_PREFIX.length()
                || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return "";
        }
        String token = authorization.substring(BEARER_PREFIX.length()).strip();
        return token.isBlank() || token.chars().anyMatch(Character::isWhitespace) ? "" : token;
    }
}
