package com.wish.rd.bootstrap.controller.admin.project;

import com.wish.rd.rag.qa.QaValidationProfileService;
import com.wish.rd.rag.qa.model.QaValidationProfile;
import com.wish.rd.rag.qa.model.QaValidationProfileCommand;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** Admin API for project QA defaults and task-specific overrides. */
@RestController
public final class QaValidationProfileController {

    private final QaValidationProfileService service;

    public QaValidationProfileController(QaValidationProfileService service) {
        this.service = service;
    }

    @GetMapping("/admin/projects/{projectId}/qa-profile")
    public QaValidationProfile getProject(@PathVariable("projectId") String projectId) {
        return service.getProject(projectId).orElseThrow(() -> notFound("project", projectId));
    }

    @PutMapping("/admin/projects/{projectId}/qa-profile")
    public QaValidationProfile updateProject(
            @PathVariable("projectId") String projectId,
            @RequestBody ProfileRequest request
    ) {
        return service.updateProject(projectId, toCommand(request));
    }

    @GetMapping("/admin/rd-tasks/{taskId}/qa-profile")
    public QaValidationProfile getTask(@PathVariable("taskId") String taskId) {
        return service.getTask(taskId).orElseThrow(() -> notFound("task", taskId));
    }

    @PutMapping("/admin/rd-tasks/{taskId}/qa-profile")
    public QaValidationProfile updateTask(
            @PathVariable("taskId") String taskId,
            @RequestBody ProfileRequest request
    ) {
        return service.updateTask(taskId, toCommand(request));
    }

    private static QaValidationProfileCommand toCommand(ProfileRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        return new QaValidationProfileCommand(
                request.mode(),
                request.baseUrl(),
                request.startCommand(),
                request.healthPath(),
                request.allowedHosts(),
                request.regressionCommands()
        );
    }

    private static ResponseStatusException notFound(String scope, String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, scope + " QA profile not found: " + id);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", exception.getMessage()));
    }

    public record ProfileRequest(
            String mode,
            String baseUrl,
            String startCommand,
            String healthPath,
            List<String> allowedHosts,
            List<String> regressionCommands
    ) {
    }
}
