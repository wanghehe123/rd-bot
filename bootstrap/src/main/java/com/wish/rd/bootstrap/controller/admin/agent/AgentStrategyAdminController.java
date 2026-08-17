package com.wish.rd.bootstrap.controller.admin.agent;

import com.wish.rd.bootstrap.executor.PiAgentExecutorProperties;
import com.wish.rd.bootstrap.executor.impl.ProjectRuntimeProfileUploadService;
import com.wish.rd.rag.project.agent.AgentStrategyProfileService;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.project.agent.model.AgentStrategyConsole;
import com.wish.rd.rag.project.agent.model.AgentStrategyImageMode;
import com.wish.rd.rag.project.agent.model.AgentStrategyProfile;
import com.wish.rd.rag.project.agent.model.AgentStrategyRoleSlot;
import com.wish.rd.rag.project.runtime.ProjectRuntimeProfileService;
import com.wish.rd.rag.project.runtime.model.ProjectRuntimeProfile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** Admin control plane for project-level four-role agent strategies. */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public final class AgentStrategyAdminController {

    private static final int MAX_DOCKERFILE_BYTES = 256 * 1024;

    private final AgentStrategyProfileService strategyService;
    private final AgentRuntimeMutationAccessPolicy mutationAccessPolicy;
    private final ObjectProvider<ProjectRuntimeProfileUploadService> uploadServiceProvider;
    private final ProjectRuntimeProfileService runtimeProfileService;
    private final boolean agentRuntimeEnabled;
    private final String defaultPiImage;
    private final String defaultPiQaImage;
    private final String defaultClaudeImage;

    /**
     * Wires the admin strategy console to existing runtime-profile and mutation-token beans.
     *
     * @param strategyService strategy aggregate orchestration
     * @param mutationAccessPolicy write-token gate
     * @param uploadServiceProvider Claude Dockerfile builder, absent when Docker execution is disabled
     * @param runtimeProfileService Claude runtime-profile store used to delete local-default images
     * @param piProperties host Pi image display names
     * @param agentRuntimeEnabled whether saved Pi slots are routed by the live executor
     * @param defaultClaudeImage global Claude Docker image display name
     */
    @Autowired
    public AgentStrategyAdminController(
            AgentStrategyProfileService strategyService,
            AgentRuntimeMutationAccessPolicy mutationAccessPolicy,
            ObjectProvider<ProjectRuntimeProfileUploadService> uploadServiceProvider,
            ObjectProvider<ProjectRuntimeProfileService> runtimeProfileService,
            PiAgentExecutorProperties piProperties,
            @Value("${rd.executor.agent-runtime.enabled:false}") boolean agentRuntimeEnabled,
            @Value("${rd.executor.docker.image:rd-bot/claude-code:local}") String defaultClaudeImage
    ) {
        this(
                strategyService,
                mutationAccessPolicy,
                uploadServiceProvider,
                runtimeProfileService == null ? null : runtimeProfileService.getIfAvailable(),
                agentRuntimeEnabled,
                piProperties == null ? PiAgentExecutorProperties.DEFAULT_IMAGE : piProperties.getImage(),
                piProperties == null ? PiAgentExecutorProperties.DEFAULT_QA_IMAGE : piProperties.getQaImage(),
                defaultClaudeImage
        );
    }

    AgentStrategyAdminController(
            AgentStrategyProfileService strategyService,
            AgentRuntimeMutationAccessPolicy mutationAccessPolicy,
            ObjectProvider<ProjectRuntimeProfileUploadService> uploadServiceProvider,
            ProjectRuntimeProfileService runtimeProfileService,
            boolean agentRuntimeEnabled,
            String defaultPiImage,
            String defaultPiQaImage,
            String defaultClaudeImage
    ) {
        this.strategyService = Objects.requireNonNull(strategyService, "strategyService must not be null");
        this.mutationAccessPolicy = Objects.requireNonNull(mutationAccessPolicy, "mutationAccessPolicy must not be null");
        this.uploadServiceProvider = uploadServiceProvider;
        this.runtimeProfileService = runtimeProfileService;
        this.agentRuntimeEnabled = agentRuntimeEnabled;
        this.defaultPiImage = textOrDefault(defaultPiImage, PiAgentExecutorProperties.DEFAULT_IMAGE);
        this.defaultPiQaImage = textOrDefault(defaultPiQaImage, PiAgentExecutorProperties.DEFAULT_QA_IMAGE);
        this.defaultClaudeImage = textOrDefault(defaultClaudeImage, "rd-bot/claude-code:local");
    }

    /**
     * Lists strategies for a project, including synthesized legacy defaults.
     *
     * @param projectId project id
     * @return console view
     */
    @GetMapping("/admin/projects/{projectId}/agent-strategies")
    public AgentStrategyConsoleView list(@PathVariable("projectId") String projectId) {
        return toView(strategyService.list(projectId));
    }

    /**
     * Returns one strategy including Dockerfile bodies.
     *
     * @param projectId project id
     * @param strategyId strategy id
     * @return strategy
     */
    @GetMapping("/admin/projects/{projectId}/agent-strategies/{strategyId}")
    public AgentStrategyProfile get(
            @PathVariable("projectId") String projectId,
            @PathVariable("strategyId") String strategyId
    ) {
        return strategyService.get(projectId, strategyId);
    }

    /**
     * Creates a four-role strategy.
     *
     * @param projectId project id
     * @param mutationToken capability token
     * @param request strategy body
     * @return stored strategy
     */
    @PostMapping(
            value = "/admin/projects/{projectId}/agent-strategies",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public AgentStrategyProfile create(
            @PathVariable("projectId") String projectId,
            @RequestHeader(value = AgentRuntimeMutationAccessPolicy.HEADER_NAME, required = false)
            String mutationToken,
            @RequestBody AgentStrategyProfile request
    ) {
        mutationAccessPolicy.requireAuthorized(mutationToken);
        return afterSave(strategyService.save(withProject(projectId, request)));
    }

    /**
     * Updates a four-role strategy.
     *
     * @param projectId project id
     * @param strategyId strategy id
     * @param mutationToken capability token
     * @param request strategy body
     * @return stored strategy
     */
    @PutMapping(
            value = "/admin/projects/{projectId}/agent-strategies/{strategyId}",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public AgentStrategyProfile update(
            @PathVariable("projectId") String projectId,
            @PathVariable("strategyId") String strategyId,
            @RequestHeader(value = AgentRuntimeMutationAccessPolicy.HEADER_NAME, required = false)
            String mutationToken,
            @RequestBody AgentStrategyProfile request
    ) {
        mutationAccessPolicy.requireAuthorized(mutationToken);
        return afterSave(strategyService.save(new AgentStrategyProfile(
                strategyId,
                projectId,
                request.name(),
                request.enabled(),
                request.version(),
                request.roles()
        )));
    }

    /**
     * Binds a strategy as the project default for every delivery role.
     *
     * @param projectId project id
     * @param strategyId strategy id
     * @param mutationToken capability token
     * @return binding acknowledgement
     */
    @PutMapping("/admin/projects/{projectId}/agent-strategies/{strategyId}/default")
    public Map<String, String> bindDefault(
            @PathVariable("projectId") String projectId,
            @PathVariable("strategyId") String strategyId,
            @RequestHeader(value = AgentRuntimeMutationAccessPolicy.HEADER_NAME, required = false)
            String mutationToken
    ) {
        mutationAccessPolicy.requireAuthorized(mutationToken);
        strategyService.bindDefault(projectId, strategyId);
        return Map.of("projectId", projectId, "strategyId", strategyId);
    }

    /**
     * Uploads a Dockerfile for one role slot.
     *
     * @param projectId project id
     * @param strategyId strategy id
     * @param role delivery role
     * @param mutationToken capability token
     * @param dockerfile uploaded file
     * @return updated strategy
     */
    @PutMapping(
            value = "/admin/projects/{projectId}/agent-strategies/{strategyId}/roles/{role}/image",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public AgentStrategyProfile uploadRoleImage(
            @PathVariable("projectId") String projectId,
            @PathVariable("strategyId") String strategyId,
            @PathVariable("role") String role,
            @RequestHeader(value = AgentRuntimeMutationAccessPolicy.HEADER_NAME, required = false)
            String mutationToken,
            @RequestPart("dockerfile") MultipartFile dockerfile
    ) {
        mutationAccessPolicy.requireAuthorized(mutationToken);
        byte[] bytes = readDockerfile(dockerfile);
        AgentStrategyRoleSlot slot = strategyService.get(projectId, strategyId).slot(role);
        if (slot.runtimeType() == AgentRuntimeType.MODEL_ONLY) {
            throw new IllegalArgumentException(slot.role() + " MODEL_ONLY does not use a runtime image");
        }
        if (slot.runtimeType() == AgentRuntimeType.CLAUDE_CODE) {
            ProjectRuntimeProfileUploadService uploadService = uploadServiceProvider == null
                    ? null
                    : uploadServiceProvider.getIfAvailable();
            if (uploadService == null) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Docker runtime uploads are unavailable because Docker execution is disabled"
                );
            }
            ProjectRuntimeProfile verified = uploadService.upload(
                    projectId,
                    slot.role(),
                    "CLAUDE_CODE",
                    dockerfile.getOriginalFilename(),
                    bytes
            );
            return strategyService.applyCustomImage(
                    projectId,
                    strategyId,
                    slot.role(),
                    verified.image(),
                    verified.dockerfileName(),
                    verified.dockerfileSha256(),
                    verified.dockerfileArtifactUri(),
                    ""
            );
        }
        return strategyService.applyCustomImage(
                projectId,
                strategyId,
                slot.role(),
                "",
                filename(dockerfile),
                sha256(bytes),
                "",
                utf8(bytes)
        );
    }

    /**
     * Restores a role slot to the host default image.
     *
     * @param projectId project id
     * @param strategyId strategy id
     * @param role delivery role
     * @param mutationToken capability token
     * @return updated strategy
     */
    @DeleteMapping("/admin/projects/{projectId}/agent-strategies/{strategyId}/roles/{role}/image")
    public AgentStrategyProfile clearRoleImage(
            @PathVariable("projectId") String projectId,
            @PathVariable("strategyId") String strategyId,
            @PathVariable("role") String role,
            @RequestHeader(value = AgentRuntimeMutationAccessPolicy.HEADER_NAME, required = false)
            String mutationToken
    ) {
        mutationAccessPolicy.requireAuthorized(mutationToken);
        AgentStrategyRoleSlot slot = strategyService.get(projectId, strategyId).slot(role);
        AgentStrategyProfile updated = strategyService.clearCustomImage(projectId, strategyId, role);
        if (slot.runtimeType() == AgentRuntimeType.CLAUDE_CODE && runtimeProfileService != null) {
            runtimeProfileService.delete(projectId, slot.role());
        }
        return updated;
    }

    /**
     * Translates domain validation failures to HTTP 400.
     *
     * @param exception validation error
     * @return message body
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", safeMessage(exception)));
    }

    /**
     * Translates unprocessable upload or storage failures to HTTP 422.
     *
     * @param exception storage or builder error
     * @return message body
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> unprocessable(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("message", safeMessage(exception)));
    }

    private AgentStrategyProfile afterSave(AgentStrategyProfile saved) {
        clearClaudeLocalDefaultImages(saved);
        return saved;
    }

    private void clearClaudeLocalDefaultImages(AgentStrategyProfile saved) {
        if (runtimeProfileService == null) {
            return;
        }
        for (AgentStrategyRoleSlot slot : saved.roles()) {
            if (slot.runtimeType() == AgentRuntimeType.CLAUDE_CODE
                    && slot.imageMode() == AgentStrategyImageMode.LOCAL_DEFAULT) {
                runtimeProfileService.delete(saved.projectId(), slot.role());
            }
        }
    }

    private AgentStrategyConsoleView toView(AgentStrategyConsole console) {
        return new AgentStrategyConsoleView(
                console.projectId(),
                console.defaultStrategyId(),
                console.synthesizedFromLegacy(),
                agentRuntimeEnabled,
                defaultPiImage,
                defaultPiQaImage,
                defaultClaudeImage,
                console.strategies()
        );
    }

    private static AgentStrategyProfile withProject(String projectId, AgentStrategyProfile request) {
        if (request == null) {
            throw new IllegalArgumentException("execution strategy must not be null");
        }
        return new AgentStrategyProfile(
                request.strategyId(),
                projectId,
                request.name(),
                request.enabled(),
                request.version(),
                request.roles()
        );
    }

    private static byte[] readDockerfile(MultipartFile dockerfile) {
        if (dockerfile == null || dockerfile.isEmpty()) {
            throw new IllegalArgumentException("dockerfile must not be blank");
        }
        try {
            byte[] bytes = dockerfile.getBytes();
            if (bytes.length > MAX_DOCKERFILE_BYTES) {
                throw new IllegalArgumentException("dockerfile exceeds 256 KiB");
            }
            return bytes;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "failed to read uploaded Dockerfile", exception);
        }
    }

    private static String filename(MultipartFile dockerfile) {
        String name = dockerfile == null ? "" : dockerfile.getOriginalFilename();
        return name == null || name.isBlank() ? "Dockerfile" : name.strip();
    }

    private static String utf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("dockerfile must be UTF-8");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static String safeMessage(Throwable exception) {
        return exception == null || exception.getMessage() == null ? "request failed" : exception.getMessage();
    }

    private static String textOrDefault(String value, String fallback) {
        String normalized = value == null ? "" : value.strip();
        return normalized.isBlank() ? fallback : normalized;
    }

    /** HTTP view that adds execution-switch and default image display fields. */
    public record AgentStrategyConsoleView(
            String projectId,
            String defaultStrategyId,
            boolean synthesizedFromLegacy,
            boolean agentRuntimeEnabled,
            String defaultPiImage,
            String defaultPiQaImage,
            String defaultClaudeImage,
            java.util.List<AgentStrategyProfile> strategies
    ) {
    }
}
