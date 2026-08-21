package com.wish.rd.bootstrap.controller.admin.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.rag.project.agent.ModelProviderCredentialService;
import com.wish.rd.rag.project.agent.ModelProviderProfileService;
import com.wish.rd.rag.project.agent.model.ModelProviderCredentialStatus;
import com.wish.rd.rag.project.agent.model.ModelProviderProfile;
import com.wish.rd.rag.project.agent.model.ModelProviderProtocol;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Admin control plane for provider routing metadata and write-only credentials. */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public final class ModelProviderAdminController {

    private final ModelProviderProfileService providerProfileService;
    private final ModelProviderCredentialService credentialService;
    private final AgentRuntimeMutationAccessPolicy mutationAccessPolicy;
    private final ObjectMapper objectMapper;

    public ModelProviderAdminController(
            ModelProviderProfileService providerProfileService,
            ModelProviderCredentialService credentialService,
            AgentRuntimeMutationAccessPolicy mutationAccessPolicy,
            ObjectMapper objectMapper
    ) {
        this.providerProfileService = Objects.requireNonNull(providerProfileService, "providerProfileService");
        this.credentialService = Objects.requireNonNull(credentialService, "credentialService");
        this.mutationAccessPolicy = Objects.requireNonNull(mutationAccessPolicy, "mutationAccessPolicy");
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @GetMapping("/admin/model-provider-profiles")
    public List<ModelProviderView> list() {
        return providerProfileService.list().stream()
                .map(this::toView)
                .toList();
    }

    @GetMapping("/admin/model-provider-profiles/{providerId}")
    public ModelProviderView get(@PathVariable("providerId") String providerId) {
        return providerProfileService.find(providerId)
                .map(this::toView)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "model provider profile not found"));
    }

    @PutMapping(
            value = "/admin/model-provider-profiles/{providerId}",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ModelProviderView upsertMetadata(
            @PathVariable("providerId") String providerId,
            @RequestHeader(value = AgentRuntimeMutationAccessPolicy.HEADER_NAME, required = false)
            String mutationToken,
            @RequestBody JsonNode body
    ) {
        mutationAccessPolicy.requireAuthorized(mutationToken);
        if (body == null || body.isNull() || body.isMissingNode()) {
            throw new IllegalArgumentException("provider metadata must not be null");
        }
        rejectSecretFields(body);
        MetadataWriteRequest request = objectMapper.convertValue(body, MetadataWriteRequest.class);
        ModelProviderProfile saved = providerProfileService.register(new ModelProviderProfile(
                providerId,
                request.displayName(),
                ModelProviderProtocol.parse(request.protocol()),
                request.baseUrl(),
                request.modelId(),
                request.credentialEnvironmentVariable(),
                Boolean.TRUE.equals(request.authHeader()),
                request.enabled() == null || request.enabled(),
                request.version() == null ? 1L : request.version()
        ));
        return toView(saved);
    }

    @PutMapping(
            value = "/admin/model-provider-profiles/{providerId}/credential",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public CredentialWriteResult putCredential(
            @PathVariable("providerId") String providerId,
            @RequestHeader(value = AgentRuntimeMutationAccessPolicy.HEADER_NAME, required = false)
            String mutationToken,
            @RequestBody CredentialWriteRequest request
    ) {
        mutationAccessPolicy.requireAuthorized(mutationToken);
        credentialService.put(providerId, request == null ? "" : request.apiKey());
        ModelProviderCredentialStatus status = credentialService.status(providerId);
        return new CredentialWriteResult(
                status.providerId(),
                status.configured(),
                status.updatedAtEpochMillis()
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", safeMessage(exception)));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", safeMessage(exception)));
    }

    private ModelProviderView toView(ModelProviderProfile profile) {
        ModelProviderCredentialStatus status = credentialService.status(profile.providerId());
        return new ModelProviderView(
                profile.providerId(),
                profile.displayName(),
                profile.protocol(),
                profile.baseUrl(),
                profile.modelId(),
                profile.credentialEnvironmentVariable(),
                profile.authHeader(),
                profile.enabled(),
                profile.version(),
                status.configured(),
                status.updatedAtEpochMillis()
        );
    }

    private static void rejectSecretFields(JsonNode body) {
        if (body != null && body.has("apiKey")) {
            throw new IllegalArgumentException("apiKey is not allowed on provider metadata");
        }
    }

    private static String safeMessage(Throwable exception) {
        return exception == null || exception.getMessage() == null
                ? "request failed"
                : exception.getMessage();
    }

    public record ModelProviderView(
            String providerId,
            String displayName,
            ModelProviderProtocol protocol,
            String baseUrl,
            String modelId,
            String credentialEnvironmentVariable,
            boolean authHeader,
            boolean enabled,
            long version,
            boolean credentialConfigured,
            Long credentialUpdatedAt
    ) {
    }

    public record CredentialWriteRequest(String apiKey) {
    }

    public record CredentialWriteResult(
            String providerId,
            boolean configured,
            Long updatedAtEpochMillis
    ) {
    }

    public record MetadataWriteRequest(
            String displayName,
            String protocol,
            String baseUrl,
            String modelId,
            String credentialEnvironmentVariable,
            Boolean authHeader,
            Boolean enabled,
            Long version
    ) {
    }
}
