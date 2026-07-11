package com.wish.rd.bootstrap.controller.admin.project;

import com.wish.rd.rag.project.template.RdProjectTaskTemplateService;
import com.wish.rd.rag.project.template.model.RdProjectTaskTemplate;
import com.wish.rd.rag.project.template.model.RdProjectTaskTemplateCommand;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/** Project task template management endpoints. */
@RestController
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class RdProjectTaskTemplateController {

    private final RdProjectTaskTemplateService service;

    public RdProjectTaskTemplateController(RdProjectTaskTemplateService service) {
        this.service = service;
    }

    @GetMapping("/admin/projects/{projectId}/task-templates/{taskType}")
    public RdProjectTaskTemplate get(
            @PathVariable("projectId") String projectId,
            @PathVariable("taskType") String taskType
    ) {
        return service.get(projectId, taskType);
    }

    @PutMapping("/admin/projects/{projectId}/task-templates/{taskType}")
    public RdProjectTaskTemplate update(
            @PathVariable("projectId") String projectId,
            @PathVariable("taskType") String taskType,
            @RequestBody RdProjectTaskTemplateCommand command
    ) {
        return service.update(projectId, taskType, command);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", exception.getMessage()));
    }
}
