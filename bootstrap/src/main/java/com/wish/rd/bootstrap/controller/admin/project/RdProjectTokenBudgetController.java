package com.wish.rd.bootstrap.controller.admin.project;

import com.wish.rd.rag.project.budget.RdProjectTokenBudgetService;
import com.wish.rd.rag.project.budget.model.RdProjectTokenBudget;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/** Project-scoped default token-budget endpoints. */
@RestController
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class RdProjectTokenBudgetController {

    private final RdProjectTokenBudgetService service;

    public RdProjectTokenBudgetController(RdProjectTokenBudgetService service) {
        this.service = service;
    }

    @GetMapping(value = "/admin/projects/{projectId}/token-budget", produces = MediaType.APPLICATION_JSON_VALUE)
    public RdProjectTokenBudget get(@PathVariable("projectId") String projectId) {
        return service.get(projectId);
    }

    @PutMapping(
            value = "/admin/projects/{projectId}/token-budget",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public RdProjectTokenBudget update(
            @PathVariable("projectId") String projectId,
            @RequestBody TokenBudgetRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request body must not be null");
        }
        return service.update(projectId, request.defaultTokenBudget());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", exception.getMessage()));
    }

    public record TokenBudgetRequest(long defaultTokenBudget) {
    }
}
