package com.wish.rd.bootstrap.controller.admin.agent;

import com.wish.rd.rag.project.agent.AgentExecutionProfileService;
import com.wish.rd.rag.project.agent.AgentExecutionProfileSnapshotStore;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfile;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.project.agent.model.AgentRuntimeCapability;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Admin control plane for registered execution profiles and immutable stage snapshots. */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public final class AgentExecutionProfileAdminController {

    private final AgentExecutionProfileService profileService;
    private final AgentExecutionProfileSnapshotStore snapshotStore;
    private final RagStreamTaskRegistry taskRegistry;
    private final AgentRuntimeMutationAccessPolicy mutationAccessPolicy;

    public AgentExecutionProfileAdminController(
            AgentExecutionProfileService profileService,
            AgentExecutionProfileSnapshotStore snapshotStore,
            RagStreamTaskRegistry taskRegistry,
            AgentRuntimeMutationAccessPolicy mutationAccessPolicy
    ) {
        this.profileService = profileService;
        this.snapshotStore = snapshotStore;
        this.taskRegistry = taskRegistry;
        this.mutationAccessPolicy = mutationAccessPolicy;
    }

    @GetMapping("/admin/projects/{projectId}/agent-execution-profiles")
    public List<AgentExecutionProfile> listProfiles(@PathVariable("projectId") String projectId) {
        return profileService.listByProject(projectId);
    }

    @PostMapping(
            value = "/admin/projects/{projectId}/agent-execution-profiles",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public AgentExecutionProfile createProfile(
            @PathVariable("projectId") String projectId,
            @RequestHeader(value = AgentRuntimeMutationAccessPolicy.HEADER_NAME, required = false)
            String mutationToken,
            @RequestBody AgentExecutionProfileRequest request
    ) {
        mutationAccessPolicy.requireAuthorized(mutationToken);
        return profileService.create(request.toProfile(projectId, request.profileId()));
    }

    @PutMapping(
            value = "/admin/projects/{projectId}/agent-execution-profiles/{profileId}",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public AgentExecutionProfile updateProfile(
            @PathVariable("projectId") String projectId,
            @PathVariable("profileId") String profileId,
            @RequestHeader(value = AgentRuntimeMutationAccessPolicy.HEADER_NAME, required = false)
            String mutationToken,
            @RequestBody AgentExecutionProfileRequest request
    ) {
        mutationAccessPolicy.requireAuthorized(mutationToken);
        return profileService.update(request.toProfile(projectId, profileId), request.version());
    }

    @PutMapping(
            value = "/admin/projects/{projectId}/agent-execution-profiles/{role}/default",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public Map<String, Object> bindProjectDefault(
            @PathVariable("projectId") String projectId,
            @PathVariable("role") String role,
            @RequestHeader(value = AgentRuntimeMutationAccessPolicy.HEADER_NAME, required = false)
            String mutationToken,
            @RequestBody ProfileReferenceRequest request
    ) {
        mutationAccessPolicy.requireAuthorized(mutationToken);
        profileService.bindProjectDefault(projectId, role, request.requiredProfileId());
        return Map.of("projectId", projectId, "role", role, "profileId", request.profileId());
    }

    @PutMapping(
            value = "/admin/rd-tasks/{taskId}/agent-execution-profile-overrides/{role}",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public Map<String, Object> setTaskOverride(
            @PathVariable("taskId") String taskId,
            @PathVariable("role") String role,
            @RequestHeader(value = AgentRuntimeMutationAccessPolicy.HEADER_NAME, required = false)
            String mutationToken,
            @RequestBody ProfileReferenceRequest request
    ) {
        mutationAccessPolicy.requireAuthorized(mutationToken);
        String projectId = requirementProjectId(taskId);
        profileService.setTaskOverride(taskId, projectId, role, request.requiredProfileId());
        return Map.of("taskId", taskId, "projectId", projectId, "role", role, "profileId", request.profileId());
    }

    @DeleteMapping("/admin/rd-tasks/{taskId}/agent-execution-profile-overrides/{role}")
    public Map<String, Object> clearTaskOverride(
            @PathVariable("taskId") String taskId,
            @PathVariable("role") String role,
            @RequestHeader(value = AgentRuntimeMutationAccessPolicy.HEADER_NAME, required = false)
            String mutationToken
    ) {
        mutationAccessPolicy.requireAuthorized(mutationToken);
        profileService.clearTaskOverride(taskId, role);
        return Map.of("taskId", taskId, "role", role, "deleted", true);
    }

    @GetMapping("/admin/rd-tasks/{taskId}/agent-execution-profile-overrides/{role}")
    public AgentExecutionProfile getTaskOverride(
            @PathVariable("taskId") String taskId,
            @PathVariable("role") String role
    ) {
        String projectId = requirementProjectId(taskId);
        return profileService.findTaskOverride(taskId, projectId, role)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "task execution profile override not found"));
    }

    @GetMapping("/admin/rd-tasks/{taskId}/stage-runs/{stageRunId}/execution-profile")
    public AgentExecutionProfileSnapshot getExecutionProfile(
            @PathVariable("taskId") String taskId,
            @PathVariable("stageRunId") String stageRunId
    ) {
        taskRegistry.getTask(taskId);
        AgentExecutionProfileSnapshot snapshot = snapshotStore.findByStageRunId(stageRunId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "execution profile snapshot not found"));
        if (!taskId.equals(snapshot.taskId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "execution profile snapshot not found");
        }
        return snapshot;
    }

    private String requirementProjectId(String taskId) {
        RdTask task = taskRegistry.getTask(taskId);
        if (!(task instanceof RdRequirementTask requirementTask) || requirementTask.projectId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "task-level runtime override requires a requirement task with a project"
            );
        }
        return requirementTask.projectId();
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

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", safeMessage(exception)));
    }

    private static String safeMessage(Throwable exception) {
        return exception == null || exception.getMessage() == null
                ? "request failed"
                : exception.getMessage();
    }

    public record ProfileReferenceRequest(String profileId) {
        private String requiredProfileId() {
            if (profileId == null || profileId.isBlank()) {
                throw new IllegalArgumentException("profileId must not be blank");
            }
            return profileId.strip();
        }
    }

    public record AgentExecutionProfileRequest(
            String profileId,
            String role,
            String name,
            AgentRuntimeType runtimeType,
            String providerProfileId,
            String modelOverride,
            String extensionSetId,
            long extensionSetVersion,
            String toolPolicyId,
            long toolPolicyVersion,
            boolean enabled,
            long version,
            List<AgentRuntimeCapability> capabilities
    ) {
        public AgentExecutionProfileRequest(
                String profileId,
                String role,
                String name,
                AgentRuntimeType runtimeType,
                String providerProfileId,
                String modelOverride,
                String extensionSetId,
                long extensionSetVersion,
                String toolPolicyId,
                long toolPolicyVersion,
                boolean enabled,
                long version
        ) {
            this(
                    profileId, role, name, runtimeType, providerProfileId, modelOverride,
                    extensionSetId, extensionSetVersion, toolPolicyId, toolPolicyVersion,
                    enabled, version, List.of()
            );
        }

        private AgentExecutionProfile toProfile(String projectId, String id) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("profileId must not be blank");
            }
            return new AgentExecutionProfile(
                    id.strip(),
                    projectId,
                    role,
                    name,
                    runtimeType,
                    providerProfileId,
                    modelOverride,
                    extensionSetId,
                    extensionSetVersion,
                    toolPolicyId,
                    toolPolicyVersion,
                    enabled,
                    version,
                    capabilities
            );
        }
    }
}
