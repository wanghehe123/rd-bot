package com.wish.rd.bootstrap.controller.admin.observability;

import com.wish.rd.engine.admin.observability.DeliveryObservabilityQueryService;
import com.wish.rd.engine.admin.observability.model.DeliveryFailurePage;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityOverview;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityQuery;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityTimeseries;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityWindow;
import com.wish.rd.engine.admin.observability.model.DeliveryTaskPage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only HTTP adapter for delivery observability. Aggregation stays in engine.
 */
@RestController
public final class DeliveryObservabilityController {

    private final DeliveryObservabilityQueryService queryService;

    /**
     * @param queryService engine query boundary
     */
    public DeliveryObservabilityController(DeliveryObservabilityQueryService queryService) {
        this.queryService = Objects.requireNonNull(queryService, "queryService must not be null");
    }

    /**
     * @param projectId concrete project or omitted for all projects
     * @param window allowlisted window token
     * @param role optional role filter
     * @param runtime optional runtime filter
     * @param provider optional provider filter
     * @param failureCategory optional failure category
     * @return overview
     */
    @GetMapping("/admin/observability/delivery/overview")
    public DeliveryObservabilityOverview overview(
            @RequestParam(value = "projectId", required = false) String projectId,
            @RequestParam(value = "window", defaultValue = "24h") String window,
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "runtime", required = false) String runtime,
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "failureCategory", required = false) String failureCategory
    ) {
        Instant now = Instant.now();
        return queryService.overview(query(projectId, window, now, role, runtime, provider, failureCategory, 1, 20), now);
    }

    /**
     * @param projectId concrete project or omitted
     * @param window allowlisted window token
     * @return timeseries
     */
    @GetMapping("/admin/observability/delivery/timeseries")
    public DeliveryObservabilityTimeseries timeseries(
            @RequestParam(value = "projectId", required = false) String projectId,
            @RequestParam(value = "window", defaultValue = "24h") String window
    ) {
        Instant now = Instant.now();
        return queryService.timeseries(query(projectId, window, now, "", "", "", "", 1, 20), now);
    }

    /**
     * @param projectId concrete project or omitted
     * @param window allowlisted window token
     * @param failureCategory optional category filter
     * @param page page
     * @param pageSize page size
     * @return failure page
     */
    @GetMapping("/admin/observability/delivery/failures")
    public DeliveryFailurePage failures(
            @RequestParam(value = "projectId", required = false) String projectId,
            @RequestParam(value = "window", defaultValue = "24h") String window,
            @RequestParam(value = "failureCategory", required = false) String failureCategory,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize
    ) {
        Instant now = Instant.now();
        return queryService.failures(
                query(projectId, window, now, "", "", "", failureCategory, page, pageSize), now);
    }

    /**
     * @param projectId concrete project or omitted
     * @param window allowlisted window token
     * @param role optional role
     * @param runtime optional runtime
     * @param provider optional provider
     * @param failureCategory optional category
     * @param page page
     * @param pageSize page size
     * @return task page
     */
    @GetMapping("/admin/observability/delivery/tasks")
    public DeliveryTaskPage tasks(
            @RequestParam(value = "projectId", required = false) String projectId,
            @RequestParam(value = "window", defaultValue = "24h") String window,
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "runtime", required = false) String runtime,
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "failureCategory", required = false) String failureCategory,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize
    ) {
        Instant now = Instant.now();
        return queryService.tasks(
                query(projectId, window, now, role, runtime, provider, failureCategory, page, pageSize), now);
    }

    /**
     * @param exception illegal query
     * @return stable 400 envelope
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", safeMessage(exception)));
    }

    /**
     * @param exception missing resource
     * @return stable 404 envelope
     */
    @ExceptionHandler(java.util.NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(java.util.NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", safeMessage(exception)));
    }

    private static DeliveryObservabilityQuery query(
            String projectId,
            String window,
            Instant now,
            String role,
            String runtime,
            String provider,
            String failureCategory,
            int page,
            int pageSize
    ) {
        return new DeliveryObservabilityQuery(
                projectId,
                DeliveryObservabilityWindow.parse(window),
                now,
                role,
                runtime,
                provider,
                failureCategory,
                page,
                pageSize
        );
    }

    private static String safeMessage(Exception exception) {
        return exception == null || exception.getMessage() == null ? "" : exception.getMessage();
    }
}
