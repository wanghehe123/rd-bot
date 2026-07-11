package com.wish.rd.bootstrap.controller.admin.project;

import com.wish.rd.rag.project.alert.RdProjectAlertConfigService;
import com.wish.rd.rag.project.alert.model.RdAlertRecipient;
import com.wish.rd.rag.project.alert.model.RdProjectAlertConfig;
import com.wish.rd.rag.project.alert.model.RdProjectAlertConfigCommand;
import com.wish.rd.rag.project.alert.model.RdProjectAlertEventType;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.NoSuchElementException;

/** Project-scoped Feishu alert configuration endpoints. */
@RestController
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class RdProjectAlertConfigController {

    private final RdProjectAlertConfigService service;

    public RdProjectAlertConfigController(RdProjectAlertConfigService service) {
        this.service = service;
    }

    @GetMapping(value = "/admin/projects/{projectId}/alert-config", produces = MediaType.APPLICATION_JSON_VALUE)
    public RdProjectAlertConfig get(@PathVariable("projectId") String projectId) {
        return service.get(projectId);
    }

    @PutMapping(
            value = "/admin/projects/{projectId}/alert-config",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public RdProjectAlertConfig update(
            @PathVariable("projectId") String projectId,
            @RequestBody AlertConfigRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request body must not be null");
        }
        return service.update(projectId, new RdProjectAlertConfigCommand(
                request.enabled(),
                request.recipients(),
                request.eventTypes(),
                request.budgetThresholdCny(),
                request.failureThreshold()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", exception.getMessage()));
    }

    public record AlertConfigRequest(
            boolean enabled,
            List<RdAlertRecipient> recipients,
            Set<RdProjectAlertEventType> eventTypes,
            BigDecimal budgetThresholdCny,
            int failureThreshold
    ) {
    }
}
