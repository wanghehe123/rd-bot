package com.wish.rd.bootstrap.controller.admin.project;

import com.wish.rd.bootstrap.executor.impl.ProjectRuntimeProfileUploadService;
import com.wish.rd.rag.project.runtime.ProjectRuntimeProfileService;
import com.wish.rd.rag.project.runtime.model.ProjectRuntimeProfile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Admin API for uploading and selecting validated Docker Claude Code role runtimes. */
@RestController
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class ProjectRuntimeProfileController {

    private final ProjectRuntimeProfileService profileService;
    private final ObjectProvider<ProjectRuntimeProfileUploadService> uploadServiceProvider;
    private final ProjectRuntimeProfileMutationAccessPolicy mutationAccessPolicy;

    public ProjectRuntimeProfileController(
            ProjectRuntimeProfileService profileService,
            ObjectProvider<ProjectRuntimeProfileUploadService> uploadServiceProvider,
            ProjectRuntimeProfileMutationAccessPolicy mutationAccessPolicy
    ) {
        this.profileService = profileService;
        this.uploadServiceProvider = uploadServiceProvider;
        this.mutationAccessPolicy = mutationAccessPolicy;
    }

    @GetMapping(value = "/admin/projects/{projectId}/runtime-profiles", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ProjectRuntimeProfile> list(@PathVariable("projectId") String projectId) {
        return profileService.list(projectId);
    }

    @PutMapping(
            value = "/admin/projects/{projectId}/runtime-profiles/{role}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ProjectRuntimeProfile upload(
            @PathVariable("projectId") String projectId,
            @PathVariable("role") String role,
            @RequestParam(value = "agentType", defaultValue = "CLAUDE_CODE") String agentType,
            @RequestHeader(value = ProjectRuntimeProfileMutationAccessPolicy.HEADER_NAME, required = false)
            String mutationToken,
            @RequestPart("dockerfile") MultipartFile dockerfile
    ) {
        mutationAccessPolicy.requireAuthorized(mutationToken);
        ProjectRuntimeProfileUploadService uploadService = uploadServiceProvider.getIfAvailable();
        if (uploadService == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Docker runtime uploads are unavailable because Docker execution is disabled"
            );
        }
        try {
            return uploadService.upload(
                    projectId,
                    role,
                    agentType,
                    dockerfile == null ? "" : dockerfile.getOriginalFilename(),
                    dockerfile == null ? new byte[0] : dockerfile.getBytes()
            );
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "failed to read uploaded Dockerfile", exception);
        }
    }

    @DeleteMapping(value = "/admin/projects/{projectId}/runtime-profiles/{role}")
    public Map<String, Boolean> delete(
            @PathVariable("projectId") String projectId,
            @PathVariable("role") String role,
            @RequestHeader(value = ProjectRuntimeProfileMutationAccessPolicy.HEADER_NAME, required = false)
            String mutationToken
    ) {
        mutationAccessPolicy.requireAuthorized(mutationToken);
        return Map.of("deleted", profileService.delete(projectId, role));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> validationFailure(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("message", exception.getMessage()));
    }
}
