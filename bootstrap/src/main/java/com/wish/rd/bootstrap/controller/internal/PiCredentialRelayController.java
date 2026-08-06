package com.wish.rd.bootstrap.controller.internal;

import com.wish.rd.bootstrap.executor.PiCredentialRelayService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Internal Host endpoint used by a Pi bridge to redeem one bounded credential lease.
 * The lease bearer token and its task/stage/provider binding are the only authorization
 * material; invalid requests receive an empty 401 response.
 */
@RestController
@ConditionalOnProperty(
        prefix = "rd.executor.pi",
        name = "credential-relay-enabled",
        havingValue = "true"
)
public class PiCredentialRelayController {

    private static final String PATH = "/internal/pi/credential-relay/redeem";
    private static final String BEARER_PREFIX = "Bearer ";

    private final PiCredentialRelayService relayService;

    /**
     * Creates the internal relay endpoint.
     *
     * @param relayService Host-side lease redemption service
     */
    public PiCredentialRelayController(PiCredentialRelayService relayService) {
        if (relayService == null) {
            throw new IllegalArgumentException("relayService must not be null");
        }
        this.relayService = relayService;
    }

    /**
     * Redeems a short-lived lease for the Pi bridge.
     *
     * @param authorization bearer lease token
     * @param request        lease binding metadata
     * @return credential for a valid lease, otherwise an empty 401 response
     */
    @PostMapping(
            value = PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<CredentialResponse> redeem(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) RedemptionRequest request
    ) {
        if (request == null) {
            return unauthorized();
        }
        String token = bearerToken(authorization);
        Optional<String> credential = relayService.redeem(
                token,
                request.taskId(),
                request.stageRunId(),
                request.providerId()
        );
        return credential
                .filter(value -> !value.isBlank())
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(new CredentialResponse(value)))
                .orElseGet(PiCredentialRelayController::unauthorized);
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

    private static ResponseEntity<CredentialResponse> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    /** Binding metadata that must match the Host-issued lease. */
    public record RedemptionRequest(String taskId, String stageRunId, String providerId) {
    }

    /** Successful response; the credential is never logged or included in failure responses. */
    public record CredentialResponse(String credential) {
    }
}
