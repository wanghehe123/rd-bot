package com.wish.rd.bootstrap.controller.observability;

import com.wish.rd.engine.admin.observability.DeliveryObservabilityQueryService;
import com.wish.rd.engine.admin.observability.model.CapacityMetric;
import com.wish.rd.engine.admin.observability.model.DataQualityStatus;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityOverview;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityQuery;
import com.wish.rd.engine.admin.observability.model.DurationHistogram;
import com.wish.rd.engine.admin.observability.model.PercentileMetric;
import com.wish.rd.engine.admin.observability.model.RatioMetric;
import com.wish.rd.engine.admin.observability.model.RoleStatusCount;
import com.wish.rd.engine.admin.observability.model.SchedulerLiveMetrics;
import com.wish.rd.engine.admin.observability.model.UsageBucket;
import com.wish.rd.rag.knowledge.projection.model.InventoryCategory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal Prometheus text endpoint for production acceptance and audit dashboards.
 */
@RestController
@ConditionalOnBean(DataSource.class)
public final class PrometheusMetricsController {

    private static final List<String> DELIVERY_ROLES = List.of(
            "REQUIREMENT_REVIEWER",
            "SOLUTION_ARCHITECT",
            "CODING_AGENT",
            "QA_AGENT"
    );

    private final DataSource dataSource;
    private final DeliveryObservabilityQueryService queryService;

    public PrometheusMetricsController(DataSource dataSource) {
        this(dataSource, null);
    }

    @Autowired
    public PrometheusMetricsController(
            DataSource dataSource,
            DeliveryObservabilityQueryService queryService
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.queryService = queryService;
    }

    @GetMapping(value = "/actuator/prometheus", produces = MediaType.TEXT_PLAIN_VALUE)
    public String prometheus() {
        return render(snapshot());
    }

    static String render(MetricsSnapshot snapshot) {
        MetricsSnapshot safe = snapshot == null ? MetricsSnapshot.empty() : snapshot;
        CollectionFlags flags = safe.flags() == null ? CollectionFlags.healthy() : safe.flags();
        StringBuilder metrics = new StringBuilder();
        appendCollectorHealth(metrics, flags);
        if (flags.deliveryAvailable()) {
            appendDeliveryMetrics(metrics, safe, flags);
        }
        if (flags.retrievalAvailable()) {
            appendRetrievalMetrics(metrics, safe.retrievalMetrics());
        }
        if (flags.controlPlaneAvailable()) {
            appendControlPlaneMetrics(metrics, safe.controlPlaneMetrics());
        }
        if (flags.projectionAvailable()) {
            appendProjectionMetrics(metrics, safe.projectionMetrics());
        }
        if (flags.inventoryAvailable()) {
            appendInventoryMetrics(metrics, safe.inventoryMetrics());
        }
        String text = metrics.toString();
        return text.replace(
                "rd_bot_observability_scrape_bytes 0\n",
                "rd_bot_observability_scrape_bytes " + text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + "\n"
        );
    }

    private static void appendDeliveryMetrics(StringBuilder metrics, MetricsSnapshot safe, CollectionFlags flags) {
        DeliveryScrape scrape = safe.deliveryScrape() == null ? DeliveryScrape.empty() : safe.deliveryScrape();
        long totalTasks = Math.max(safe.totalTaskCount(), 0);
        long successTasks = Math.max(safe.successTaskCount(), 0);
        long validationTotal = Math.max(safe.validationTotalCount(), 0);
        long validationPassed = Math.max(safe.validationPassedCount(), 0);
        long humanInterventionTasks = Math.max(safe.humanInterventionTaskCount(), 0);
        long retryCount = Math.max(safe.retryCount(), 0);
        Double repairSuccessRate = scrape.present()
                ? scrape.successRate()
                : ratio(successTasks, totalTasks);
        Double validationPassRate = scrape.present()
                ? scrape.qaPassRate()
                : ratio(validationPassed, validationTotal);
        Double prCreationRate = scrape.present()
                ? scrape.prCreationRate()
                : ratio(safe.prCreatedTaskCount(), totalTasks);
        Double humanInterventionRate = scrape.present()
                ? scrape.humanInterventionRate()
                : ratio(humanInterventionTasks, totalTasks);
        Double retryRate = scrape.present()
                ? scrape.retryRate()
                : ratio(retryCount, Math.max(validationTotal, totalTasks));
        Double meanRepair = scrape.present()
                ? scrape.meanEndToEndSeconds()
                : Double.valueOf(Math.max(safe.meanTimeToRepairSeconds(), 0));
        Double contextLatency = scrape.present()
                ? scrape.contextLatencySeconds()
                : flags.contextBuildLatencySeconds();
        metrics.append("# HELP rd_bot_context_build_latency_seconds Mean context-phase duration from adjacent task status entered_at.\n")
                .append("# TYPE rd_bot_context_build_latency_seconds gauge\n");
        appendOptionalGauge(metrics, "rd_bot_context_build_latency_seconds", contextLatency);
        metrics.append("# HELP rd_bot_repair_success_rate Terminal delivery success rate. Running tasks are excluded from the denominator.\n")
                .append("# TYPE rd_bot_repair_success_rate gauge\n");
        appendOptionalGauge(metrics, "rd_bot_repair_success_rate", repairSuccessRate);
        metrics.append("# HELP rd_bot_validation_pass_rate Finished QA attempts that succeeded.\n")
                .append("# TYPE rd_bot_validation_pass_rate gauge\n");
        appendOptionalGauge(metrics, "rd_bot_validation_pass_rate", validationPassRate);
        metrics.append("# HELP rd_bot_pr_creation_rate Terminal deliveries that produced a pull request.\n")
                .append("# TYPE rd_bot_pr_creation_rate gauge\n");
        appendOptionalGauge(metrics, "rd_bot_pr_creation_rate", prCreationRate);
        metrics.append("# HELP rd_bot_human_intervention_rate Terminal deliveries that need a human.\n")
                .append("# TYPE rd_bot_human_intervention_rate gauge\n");
        appendOptionalGauge(metrics, "rd_bot_human_intervention_rate", humanInterventionRate);
        metrics.append("# HELP rd_bot_retry_rate Finished stage runs with attempt_no greater than 1.\n")
                .append("# TYPE rd_bot_retry_rate gauge\n");
        appendOptionalGauge(metrics, "rd_bot_retry_rate", retryRate);
        metrics.append("# HELP rd_bot_mean_time_to_repair_seconds Mean accepted-to-terminal duration of judged deliveries.\n")
                .append("# TYPE rd_bot_mean_time_to_repair_seconds gauge\n");
        appendOptionalGauge(metrics, "rd_bot_mean_time_to_repair_seconds", meanRepair);
        metrics.append("# HELP rd_bot_top_failure_categories RD-Bot top failure categories.\n")
                .append("# TYPE rd_bot_top_failure_categories gauge\n");
        Map<String, Long> failureCategories = scrape.present() && !scrape.failureCategories().isEmpty()
                ? scrape.failureCategories()
                : safe.failureCategories();
        if (failureCategories.isEmpty()) {
            metrics.append("rd_bot_top_failure_categories{category=\"NONE\"} 0\n");
        } else {
            Map<String, Long> folded = new LinkedHashMap<>();
            failureCategories.forEach((category, count) ->
                    folded.merge(allowlistedFailureCategory(category), Math.max(count, 0L), Long::sum));
            folded.forEach((category, count) -> metrics
                    .append("rd_bot_top_failure_categories{category=\"")
                    .append(label(category))
                    .append("\"} ")
                    .append(count)
                    .append('\n'));
        }
        appendDeliveryV2(metrics, safe, scrape);
        metrics.append("# HELP rd_bot_agent_stage_total RD-Bot agent stage series by role and status.\n")
                .append("# TYPE rd_bot_agent_stage_total gauge\n");
        List<StageMetric> stageMetrics = scrape.present() && !scrape.stageMetrics().isEmpty()
                ? scrape.stageMetrics()
                : safe.stageMetrics();
        for (StageMetric stage : stageMetricsWithDefaultRoles(stageMetrics)) {
            metrics.append("rd_bot_agent_stage_total{role=\"")
                    .append(label(stage.role()))
                    .append("\",status=\"")
                    .append(label(stage.status()))
                    .append("\"} ")
                    .append(Math.max(stage.count(), 0))
                    .append('\n');
        }
    }

    private static void appendCollectorHealth(StringBuilder metrics, CollectionFlags flags) {
        metrics.append("# HELP rd_bot_observability_collection_success 1 if the named collector succeeded.\n")
                .append("# TYPE rd_bot_observability_collection_success gauge\n")
                .append("rd_bot_observability_collection_success{source=\"delivery_ledger\"} ")
                .append(flags.deliveryAvailable() ? 1 : 0).append('\n')
                .append("rd_bot_observability_collection_success{source=\"retrieval_ledger\"} ")
                .append(flags.retrievalAvailable() ? 1 : 0).append('\n')
                .append("rd_bot_observability_collection_success{source=\"control_plane\"} ")
                .append(flags.controlPlaneAvailable() ? 1 : 0).append('\n')
                .append("rd_bot_observability_collection_success{source=\"knowledge_projection\"} ")
                .append(flags.projectionAvailable() ? 1 : 0).append('\n')
                .append("rd_bot_observability_collection_success{source=\"knowledge_inventory\"} ")
                .append(flags.inventoryAvailable() ? 1 : 0).append('\n')
                .append("# HELP rd_bot_observability_last_success_unixtime Unix time of last successful collector snapshot.\n")
                .append("# TYPE rd_bot_observability_last_success_unixtime gauge\n")
                .append("rd_bot_observability_last_success_unixtime{source=\"delivery_ledger\"} ")
                .append(flags.deliveryAvailable() ? flags.generatedAtUnix() : 0).append('\n')
                .append("# HELP rd_bot_observability_scrape_duration_seconds Time spent building this scrape.\n")
                .append("# TYPE rd_bot_observability_scrape_duration_seconds gauge\n")
                .append("rd_bot_observability_scrape_duration_seconds ")
                .append(format(Math.max(flags.scrapeDurationSeconds(), 0D))).append('\n')
                .append("# HELP rd_bot_observability_scrape_bytes Prometheus text size in bytes.\n")
                .append("# TYPE rd_bot_observability_scrape_bytes gauge\n")
                .append("rd_bot_observability_scrape_bytes 0\n")
                .append("# HELP rd_bot_observability_cache_fresh 1 if this scrape used a fresh snapshot.\n")
                .append("# TYPE rd_bot_observability_cache_fresh gauge\n")
                .append("rd_bot_observability_cache_fresh ")
                .append(flags.cacheFresh() ? 1 : 0).append('\n')
                .append("# HELP rd_bot_observability_invalid_observations_total Dropped invalid duration or usage samples.\n")
                .append("# TYPE rd_bot_observability_invalid_observations_total counter\n")
                .append("rd_bot_observability_invalid_observations_total{source=\"delivery_ledger\",reason=\"negative_duration\"} ")
                .append(Math.max(flags.invalidObservations(), 0L)).append('\n');
    }

    private static void appendDeliveryV2(StringBuilder metrics, MetricsSnapshot safe, DeliveryScrape scrape) {
        DeliveryScrape view = scrape == null || !scrape.present() ? DeliveryScrape.fromLegacy(safe) : scrape;
        metrics.append("# HELP rd_bot_delivery_accepted_total Requirement tasks accepted in the scrape window.\n")
                .append("# TYPE rd_bot_delivery_accepted_total counter\n")
                .append("rd_bot_delivery_accepted_total ").append(Math.max(view.accepted(), 0L)).append('\n')
                .append("# HELP rd_bot_delivery_active Non-terminal requirement tasks at scrape time.\n")
                .append("# TYPE rd_bot_delivery_active gauge\n")
                .append("rd_bot_delivery_active{phase=\"running\"} ").append(Math.max(view.running(), 0L)).append('\n')
                .append("# HELP rd_bot_delivery_completed_total Terminal deliveries by outcome.\n")
                .append("# TYPE rd_bot_delivery_completed_total counter\n")
                .append("rd_bot_delivery_completed_total{outcome=\"success\"} ").append(Math.max(view.success(), 0L)).append('\n')
                .append("rd_bot_delivery_completed_total{outcome=\"failure\"} ").append(Math.max(view.failure(), 0L)).append('\n')
                .append("rd_bot_delivery_completed_total{outcome=\"cancelled\"} ").append(Math.max(view.cancelled(), 0L)).append('\n');
        metrics.append("# HELP rd_bot_delivery_duration_seconds Task phase durations in seconds.\n")
                .append("# TYPE rd_bot_delivery_duration_seconds histogram\n");
        appendDurationHistogram(metrics, "rd_bot_delivery_duration_seconds", "end_to_end", "ALL", "unknown",
                view.durationHistograms().getOrDefault("end_to_end", DurationHistogram.empty()));
        for (String phase : List.of("material", "context", "plan", "policy", "execution", "validation",
                "publication", "reporting")) {
            DurationHistogram histogram = view.durationHistograms().get(phase);
            if (histogram != null && histogram.count() > 0L) {
                appendDurationHistogram(metrics, "rd_bot_delivery_duration_seconds", phase, "ALL", "unknown", histogram);
            }
        }
        view.durationHistograms().forEach((key, histogram) -> {
            if (key.startsWith("role:") && histogram.count() > 0L) {
                appendDurationHistogram(metrics, "rd_bot_delivery_duration_seconds", "role",
                        key.substring("role:".length()), "unknown", histogram);
            }
        });
        metrics.append("# HELP rd_bot_delivery_queue_backlog Durable waiting stage commands.\n")
                .append("# TYPE rd_bot_delivery_queue_backlog gauge\n")
                .append("rd_bot_delivery_queue_backlog{status=\"WAITING\",resource=\"GENERIC\"} ")
                .append(Math.max(view.queueBacklog(), 0L)).append('\n');
        metrics.append("# HELP rd_bot_delivery_queue_oldest_seconds Age of the oldest durable waiting command.\n")
                .append("# TYPE rd_bot_delivery_queue_oldest_seconds gauge\n");
        if (!Double.isNaN(view.oldestQueueAgeSeconds())) {
            metrics.append("rd_bot_delivery_queue_oldest_seconds{status=\"WAITING\",resource=\"GENERIC\"} ")
                    .append(format(Math.max(view.oldestQueueAgeSeconds(), 0D))).append('\n');
        }
        metrics.append("# HELP rd_bot_delivery_queue_wait_seconds Process-window queue wait in seconds.\n")
                .append("# TYPE rd_bot_delivery_queue_wait_seconds histogram\n");
        appendPlainHistogram(metrics, "rd_bot_delivery_queue_wait_seconds", view.scheduler().queueWait());
        metrics.append("# HELP rd_bot_delivery_queue_service_seconds Process-window service time in seconds.\n")
                .append("# TYPE rd_bot_delivery_queue_service_seconds histogram\n");
        appendPlainHistogram(metrics, "rd_bot_delivery_queue_service_seconds", view.scheduler().service());
        metrics.append("# HELP rd_bot_delivery_resource_in_use Current in-flight claims by resource.\n")
                .append("# TYPE rd_bot_delivery_resource_in_use gauge\n");
        if (view.capacities().isEmpty()) {
            metrics.append("rd_bot_delivery_resource_in_use{resource=\"GENERIC\"} 0\n");
        } else {
            for (CapacityMetric capacity : view.capacities()) {
                metrics.append("rd_bot_delivery_resource_in_use{resource=\"")
                        .append(label(capacity.resource())).append("\"} ")
                        .append(capacity.inUse()).append('\n');
            }
        }
        metrics.append("# HELP rd_bot_delivery_resource_capacity Configured resource capacity.\n")
                .append("# TYPE rd_bot_delivery_resource_capacity gauge\n");
        if (view.capacities().isEmpty()) {
            metrics.append("rd_bot_delivery_resource_capacity{resource=\"GENERIC\"} 0\n");
        } else {
            for (CapacityMetric capacity : view.capacities()) {
                metrics.append("rd_bot_delivery_resource_capacity{resource=\"")
                        .append(label(capacity.resource())).append("\"} ")
                        .append(capacity.capacity()).append('\n');
            }
        }
        if (view.scheduler().available()) {
            metrics.append("# HELP rd_bot_delivery_claim_total Stage claims in this process.\n")
                    .append("# TYPE rd_bot_delivery_claim_total counter\n")
                    .append("rd_bot_delivery_claim_total ").append(view.scheduler().claimed()).append('\n')
                    .append("# HELP rd_bot_delivery_retry_total Stage retries in this process.\n")
                    .append("# TYPE rd_bot_delivery_retry_total counter\n")
                    .append("rd_bot_delivery_retry_total ").append(view.scheduler().retries()).append('\n')
                    .append("# HELP rd_bot_delivery_lease_lost_total Lease losses in this process.\n")
                    .append("# TYPE rd_bot_delivery_lease_lost_total counter\n")
                    .append("rd_bot_delivery_lease_lost_total ").append(view.scheduler().leaseLost()).append('\n')
                    .append("# HELP rd_bot_delivery_queue_rejected_total Thread-pool or queue rejections in this process.\n")
                    .append("# TYPE rd_bot_delivery_queue_rejected_total counter\n")
                    .append("rd_bot_delivery_queue_rejected_total ").append(view.scheduler().queueRejections()).append('\n');
        }
        metrics.append("# HELP rd_bot_delivery_estimated_cost_cny_total Estimated Provider cost in CNY, not a bill.\n")
                .append("# TYPE rd_bot_delivery_estimated_cost_cny_total counter\n");
        metrics.append("# HELP rd_bot_delivery_tokens_total Deduplicated token counts.\n")
                .append("# TYPE rd_bot_delivery_tokens_total counter\n");
        metrics.append("# HELP rd_bot_delivery_ttft_seconds First non-thinking assistant text minus request start.\n")
                .append("# TYPE rd_bot_delivery_ttft_seconds histogram\n");
        metrics.append("# HELP rd_bot_delivery_first_response_seconds First PROVIDER_RESPONDED minus request start.\n")
                .append("# TYPE rd_bot_delivery_first_response_seconds histogram\n");
        if (view.usageBuckets().isEmpty()) {
            metrics.append("rd_bot_delivery_estimated_cost_cny_total{runtime=\"unknown\",provider=\"OTHER\",role=\"CODING_AGENT\"} 0\n");
            metrics.append("rd_bot_delivery_tokens_total{runtime=\"unknown\",provider=\"OTHER\",role=\"CODING_AGENT\",direction=\"input\",cache=\"none\"} 0\n");
            appendDurationHistogram(metrics, "rd_bot_delivery_ttft_seconds", "", "CODING_AGENT", "unknown",
                    DurationHistogram.empty());
            appendDurationHistogram(metrics, "rd_bot_delivery_first_response_seconds", "", "CODING_AGENT", "unknown",
                    DurationHistogram.empty());
        } else {
            for (UsageBucket bucket : view.usageBuckets()) {
                if (bucket.costAvailable()) {
                    metrics.append("rd_bot_delivery_estimated_cost_cny_total{runtime=\"")
                            .append(label(bucket.runtime())).append("\",provider=\"")
                            .append(label(bucket.provider())).append("\",role=\"")
                            .append(label(bucket.role())).append("\"} ")
                            .append(format(bucket.estimatedCostCny())).append('\n');
                }
                appendToken(metrics, bucket, "input", "none", bucket.inputTokens());
                appendToken(metrics, bucket, "output", "none", bucket.outputTokens());
                appendToken(metrics, bucket, "input", "read", bucket.cacheTokens());
                if (bucket.ttft().count() > 0L) {
                    appendDurationHistogram(metrics, "rd_bot_delivery_ttft_seconds", "",
                            bucket.role(), bucket.runtime(), bucket.ttft());
                }
                if (bucket.firstResponse().count() > 0L) {
                    appendDurationHistogram(metrics, "rd_bot_delivery_first_response_seconds", "",
                            bucket.role(), bucket.runtime(), bucket.firstResponse());
                }
            }
        }
    }

    private static void appendToken(
            StringBuilder metrics,
            UsageBucket bucket,
            String direction,
            String cache,
            long tokens
    ) {
        metrics.append("rd_bot_delivery_tokens_total{runtime=\"")
                .append(label(bucket.runtime()))
                .append("\",provider=\"")
                .append(label(bucket.provider()))
                .append("\",role=\"")
                .append(label(bucket.role()))
                .append("\",direction=\"")
                .append(direction)
                .append("\",cache=\"")
                .append(cache)
                .append("\"} ")
                .append(tokens)
                .append('\n');
    }

    private static void appendDurationHistogram(
            StringBuilder metrics,
            String name,
            String phase,
            String role,
            String runtime,
            DurationHistogram histogram
    ) {
        DurationHistogram safe = histogram == null ? DurationHistogram.empty() : histogram;
        String labels = phase.isBlank()
                ? "role=\"" + label(role) + "\",runtime=\"" + label(runtime) + "\""
                : "phase=\"" + label(phase) + "\",role=\"" + label(role) + "\",runtime=\"" + label(runtime) + "\"";
        long previous = 0L;
        for (Map.Entry<Double, Long> bucket : safe.cumulativeBuckets().entrySet()) {
            String le = Double.isInfinite(bucket.getKey()) ? "+Inf" : formatBucket(bucket.getKey());
            long count = Math.max(previous, bucket.getValue());
            previous = count;
            metrics.append(name).append("_bucket{").append(labels).append(",le=\"").append(le).append("\"} ")
                    .append(count).append('\n');
        }
        metrics.append(name).append("_count{").append(labels).append("} ").append(safe.count()).append('\n')
                .append(name).append("_sum{").append(labels).append("} ").append(format(safe.sumSeconds())).append('\n');
    }

    private static void appendPlainHistogram(StringBuilder metrics, String name, DurationHistogram histogram) {
        DurationHistogram safe = histogram == null ? DurationHistogram.empty() : histogram;
        long previous = 0L;
        for (Map.Entry<Double, Long> bucket : safe.cumulativeBuckets().entrySet()) {
            String le = Double.isInfinite(bucket.getKey()) ? "+Inf" : formatBucket(bucket.getKey());
            long count = Math.max(previous, bucket.getValue());
            previous = count;
            metrics.append(name).append("_bucket{le=\"").append(le).append("\"} ").append(count).append('\n');
        }
        metrics.append(name).append("_count ").append(safe.count()).append('\n')
                .append(name).append("_sum ").append(format(safe.sumSeconds())).append('\n');
    }

    private static String formatBucket(double bound) {
        if (bound == Math.rint(bound) && !Double.isInfinite(bound)) {
            return String.format(Locale.ROOT, "%.0f", bound);
        }
        return String.format(Locale.ROOT, "%s", bound);
    }

    private static String allowlistedFailureCategory(String raw) {
        String value = label(raw).toUpperCase(Locale.ROOT);
        return switch (value) {
            case "TIMEOUT", "PROVIDER", "VALIDATION", "BUDGET", "LEASE", "CANCELLED",
                    "SCOPE_VIOLATION", "UNKNOWN", "NONE" -> value;
            default -> "UNKNOWN";
        };
    }

    private static void appendRetrievalMetrics(StringBuilder metrics, RetrievalMetrics retrieval) {
        RetrievalMetrics safe = retrieval == null ? RetrievalMetrics.empty() : retrieval;
        metrics.append("# HELP rd_bot_rag_run_total Persisted RAG retrieval runs by current status.\n")
                .append("# TYPE rd_bot_rag_run_total gauge\n");
        if (safe.runStatuses().isEmpty()) {
            metrics.append("rd_bot_rag_run_total{status=\"NO_DATA\"} 0\n");
        } else {
            safe.runStatuses().forEach((status, count) -> metrics
                    .append("rd_bot_rag_run_total{status=\"")
                    .append(label(status))
                    .append("\"} ")
                    .append(Math.max(count, 0))
                    .append('\n'));
        }
        metrics.append("# HELP rd_bot_rag_run_duration_seconds Mean persisted RAG retrieval run duration.\n")
                .append("# TYPE rd_bot_rag_run_duration_seconds gauge\n")
                .append("rd_bot_rag_run_duration_seconds ").append(format(safe.meanRunDurationSeconds())).append('\n')
                .append("# HELP rd_bot_rag_step_duration_seconds Mean persisted RAG retrieval step duration.\n")
                .append("# TYPE rd_bot_rag_step_duration_seconds gauge\n")
                .append("rd_bot_rag_step_duration_seconds ").append(format(safe.meanStepDurationSeconds())).append('\n')
                .append("# HELP rd_bot_rag_iteration_total Total completed retrieval iterations.\n")
                .append("# TYPE rd_bot_rag_iteration_total gauge\n")
                .append("rd_bot_rag_iteration_total ").append(Math.max(safe.iterationTotal(), 0)).append('\n')
                .append("# HELP rd_bot_rag_degraded_total Retrieval runs accepted with degraded evidence.\n")
                .append("# TYPE rd_bot_rag_degraded_total gauge\n")
                .append("rd_bot_rag_degraded_total ").append(Math.max(safe.degradedTotal(), 0)).append('\n')
                .append("# HELP rd_bot_rag_scope_violation_total Retrieval scope violations.\n")
                .append("# TYPE rd_bot_rag_scope_violation_total gauge\n")
                .append("rd_bot_rag_scope_violation_total ").append(Math.max(safe.scopeViolationTotal(), 0)).append('\n')
                .append("# HELP rd_bot_rag_context_budget_ratio Query preview use against the configured context budget.\n")
                .append("# TYPE rd_bot_rag_context_budget_ratio gauge\n")
                .append("rd_bot_rag_context_budget_ratio ").append(format(safe.contextBudgetRatio())).append('\n');
    }

    private static void appendControlPlaneMetrics(StringBuilder metrics, ControlPlaneMetrics controlPlane) {
        ControlPlaneMetrics safe = controlPlane == null ? ControlPlaneMetrics.empty() : controlPlane;
        metrics.append("# HELP rd_bot_task_retry_checkpoint_total Persisted task retry checkpoints by status.\n")
                .append("# TYPE rd_bot_task_retry_checkpoint_total gauge\n");
        appendStatusSeries(metrics, "rd_bot_task_retry_checkpoint_total", safe.retryStatuses());
        metrics.append("# HELP rd_bot_ai_review_run_total Persisted AI delivery reviews by status.\n")
                .append("# TYPE rd_bot_ai_review_run_total gauge\n");
        appendStatusSeries(metrics, "rd_bot_ai_review_run_total", safe.aiReviewStatuses());
        metrics.append("# HELP rd_bot_ai_review_score Mean score of completed AI delivery reviews.\n")
                .append("# TYPE rd_bot_ai_review_score gauge\n")
                .append("rd_bot_ai_review_score ").append(format(safe.meanAiReviewScore())).append('\n')
                .append("# HELP rd_bot_ai_review_duration_seconds Mean persisted AI delivery review duration.\n")
                .append("# TYPE rd_bot_ai_review_duration_seconds gauge\n")
                .append("rd_bot_ai_review_duration_seconds ")
                .append(format(safe.meanAiReviewDurationSeconds())).append('\n');
    }

    private static void appendProjectionMetrics(StringBuilder metrics, ProjectionMetrics projection) {
        ProjectionMetrics safe = projection == null ? ProjectionMetrics.empty() : projection;
        metrics.append("# HELP rd_bot_knowledge_projection_outbox_total External index outbox operations by status.\n")
                .append("# TYPE rd_bot_knowledge_projection_outbox_total gauge\n");
        appendStatusSeries(metrics, "rd_bot_knowledge_projection_outbox_total", safe.outboxStatuses());
        metrics.append("# HELP rd_bot_knowledge_projection_binding_total External index bindings by projection status.\n")
                .append("# TYPE rd_bot_knowledge_projection_binding_total gauge\n");
        appendStatusSeries(metrics, "rd_bot_knowledge_projection_binding_total", safe.bindingStatuses());
        metrics.append("# HELP rd_bot_knowledge_projection_stuck_total Outbox rows parked for a human or dead-lettered.\n")
                .append("# TYPE rd_bot_knowledge_projection_stuck_total gauge\n")
                .append("rd_bot_knowledge_projection_stuck_total ").append(safe.stuckTotal()).append('\n')
                .append("# HELP rd_bot_knowledge_projection_oldest_pending_seconds Age of the oldest unconverged outbox row.\n")
                .append("# TYPE rd_bot_knowledge_projection_oldest_pending_seconds gauge\n")
                .append("rd_bot_knowledge_projection_oldest_pending_seconds ")
                .append(format(safe.oldestPendingSeconds())).append('\n');
    }

    private static void appendInventoryMetrics(StringBuilder metrics, InventoryMetrics inventory) {
        InventoryMetrics safe = inventory == null ? InventoryMetrics.empty() : inventory;
        metrics.append("# HELP rd_bot_knowledge_inventory_documents_total Knowledge documents by inventory audit category.\n")
                .append("# TYPE rd_bot_knowledge_inventory_documents_total gauge\n");
        LinkedHashMap<String, Long> categories = new LinkedHashMap<>();
        for (InventoryCategory category : InventoryCategory.values()) {
            categories.put(category.name(), 0L);
        }
        safe.categoryCounts().forEach(categories::put);
        categories.forEach((category, count) -> metrics.append("rd_bot_knowledge_inventory_documents_total{category=\"")
                .append(label(category))
                .append("\"} ")
                .append(Math.max(count, 0))
                .append('\n'));
        metrics.append("# HELP rd_bot_knowledge_inventory_backfill_pending Eligible documents still waiting for projection backfill.\n")
                .append("# TYPE rd_bot_knowledge_inventory_backfill_pending gauge\n")
                .append("rd_bot_knowledge_inventory_backfill_pending ")
                .append(Math.max(safe.pendingBackfill(), 0L))
                .append('\n');
    }

    private static void appendStatusSeries(StringBuilder metrics, String name, Map<String, Long> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            metrics.append(name).append("{status=\"NO_DATA\"} 0\n");
            return;
        }
        statuses.forEach((status, count) -> metrics.append(name)
                .append("{status=\"").append(label(status)).append("\"} ")
                .append(Math.max(count, 0)).append('\n'));
    }

    private MetricsSnapshot snapshot() {
        long startedNanos = System.nanoTime();
        DeliveryContribution delivery = collectDeliveryFromQueryService();
        try (Connection connection = dataSource.getConnection()) {
            if (!delivery.fromQueryService()) {
                delivery = collectDeliveryFromSql(connection);
            }
            RetrievalOutcome retrieval = collectRetrieval(connection);
            ControlPlaneOutcome controlPlane = collectControlPlane(connection);
            ProjectionOutcome projection = collectProjection(connection);
            InventoryOutcome inventory = collectInventory(connection);
            long generatedAtUnix = delivery.generatedAtUnix() > 0L
                    ? delivery.generatedAtUnix()
                    : System.currentTimeMillis() / 1000L;
            double scrapeSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0D;
            DeliveryScrape scrape = delivery.scrape();
            return new MetricsSnapshot(
                    delivery.totalTasks(),
                    delivery.successTasks(),
                    delivery.validationTotal(),
                    delivery.validationPassed(),
                    delivery.humanInterventionTasks(),
                    delivery.prCreatedTasks(),
                    delivery.retryCount(),
                    delivery.meanTimeToRepairSeconds(),
                    delivery.failureCategories(),
                    delivery.stageMetrics(),
                    retrieval.metrics(),
                    controlPlane.metrics(),
                    projection.metrics(),
                    inventory.metrics(),
                    new CollectionFlags(
                            delivery.available(),
                            retrieval.available(),
                            controlPlane.available(),
                            projection.available(),
                            inventory.available(),
                            delivery.contextLatencySeconds(),
                            generatedAtUnix,
                            scrapeSeconds,
                            delivery.invalidObservations(),
                            delivery.cacheFresh()
                    ),
                    scrape
            );
        } catch (Exception ignored) {
            if (delivery.fromQueryService() && delivery.available()) {
                double scrapeSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0D;
                return new MetricsSnapshot(
                        delivery.totalTasks(),
                        delivery.successTasks(),
                        delivery.validationTotal(),
                        delivery.validationPassed(),
                        delivery.humanInterventionTasks(),
                        delivery.prCreatedTasks(),
                        delivery.retryCount(),
                        delivery.meanTimeToRepairSeconds(),
                        delivery.failureCategories(),
                        delivery.stageMetrics(),
                        RetrievalMetrics.empty(),
                        ControlPlaneMetrics.empty(),
                        ProjectionMetrics.empty(),
                        InventoryMetrics.empty(),
                        new CollectionFlags(
                                delivery.available(),
                                false,
                                false,
                                false,
                                false,
                                delivery.contextLatencySeconds(),
                                delivery.generatedAtUnix(),
                                scrapeSeconds,
                                delivery.invalidObservations(),
                                delivery.cacheFresh()
                        ),
                        delivery.scrape()
                );
            }
            return MetricsSnapshot.collectionFailed();
        }
    }

    private DeliveryContribution collectDeliveryFromQueryService() {
        if (queryService == null) {
            return DeliveryContribution.sqlPlaceholder();
        }
        try {
            Instant now = Instant.now();
            DeliveryObservabilityOverview overview = queryService.overview(
                    DeliveryObservabilityQuery.overview("", "24h", now), now);
            boolean ledgerAvailable = overview.dataQuality().stream()
                    .filter(item -> "delivery_ledger".equals(item.source()))
                    .findFirst()
                    .map(DataQualityStatus::available)
                    .orElse(overview.successRate().available());
            boolean cacheFresh = overview.dataQuality().stream().noneMatch(DataQualityStatus::stale);
            DeliveryScrape scrape = DeliveryScrape.from(overview);
            RatioMetric qa = overview.qaPassRate();
            RatioMetric retry = overview.retryRate();
            RatioMetric human = overview.humanInterventionRate();
            RatioMetric pr = overview.prCreationRate();
            PercentileMetric context = overview.phaseLatency().get("context");
            Double contextLatency = context != null && context.available() && !context.noSample()
                    ? context.meanSeconds()
                    : null;
            double meanRepair = overview.endToEndLatency().available() && !overview.endToEndLatency().noSample()
                    ? overview.endToEndLatency().meanSeconds()
                    : 0D;
            List<StageMetric> stages = overview.stageTotals().stream()
                    .map(item -> new StageMetric(item.role(), item.status(), item.count()))
                    .toList();
            return new DeliveryContribution(
                    true,
                    ledgerAvailable,
                    cacheFresh,
                    overview.acceptedCount(),
                    scrape.success(),
                    qa.available() ? qa.denominator() : 0L,
                    qa.available() ? qa.numerator() : 0L,
                    human.available() ? human.numerator() : 0L,
                    pr.available() ? pr.numerator() : 0L,
                    retry.available() ? retry.numerator() : 0L,
                    meanRepair,
                    overview.failureCategories(),
                    stages,
                    contextLatency,
                    overview.generatedAtEpochMillis() / 1000L,
                    overview.invalidObservations(),
                    scrape
            );
        } catch (RuntimeException ignored) {
            return new DeliveryContribution(
                    true, false, false, 0, 0, 0, 0, 0, 0, 0, 0D, Map.of(), List.of(),
                    null, 0L, 0L, DeliveryScrape.empty());
        }
    }

    private static DeliveryContribution collectDeliveryFromSql(Connection connection) throws Exception {
        long totalTasks = count(connection, "SELECT COUNT(*) FROM rd_tasks WHERE task_type = 'REQUIREMENT'");
        long successTasks = count(
                connection,
                "SELECT COUNT(*) FROM rd_tasks WHERE task_type = 'REQUIREMENT' AND status IN ('COMPLETED','COMMITTED','MERGED')"
        );
        long prCreatedTasks = count(
                connection,
                "SELECT COUNT(*) FROM rd_tasks WHERE task_type = 'REQUIREMENT' AND pull_request_url <> ''"
        );
        long humanInterventionTasks = count(
                connection,
                "SELECT COUNT(*) FROM rd_tasks WHERE task_type = 'REQUIREMENT' AND status = 'FAILED_NEEDS_HUMAN'"
        );
        long validationTotal = count(connection, "SELECT COUNT(*) FROM rd_agent_stage_runs WHERE role = 'QA_AGENT'");
        long validationPassed = count(
                connection,
                "SELECT COUNT(*) FROM rd_agent_stage_runs WHERE role = 'QA_AGENT' AND status = 'SUCCEEDED'"
        );
        long retryCount = count(connection, "SELECT COUNT(*) FROM rd_agent_stage_runs WHERE attempt_no > 1");
        double meanTimeToRepairSeconds = decimal(
                connection,
                """
                        SELECT COALESCE(AVG(EXTRACT(EPOCH FROM (updated_at - created_at))), 0)
                        FROM rd_tasks
                        WHERE task_type = 'REQUIREMENT'
                          AND status IN ('COMPLETED','COMMITTED','MERGED','REJECTED','FAILED_RETRYABLE','FAILED_NEEDS_HUMAN','DEAD_LETTERED')
                        """
        );
        return new DeliveryContribution(
                false,
                true,
                true,
                totalTasks,
                successTasks,
                validationTotal,
                validationPassed,
                humanInterventionTasks,
                prCreatedTasks,
                retryCount,
                meanTimeToRepairSeconds,
                failureCategories(connection),
                stageMetrics(connection),
                contextBuildLatencySeconds(connection),
                System.currentTimeMillis() / 1000L,
                0L,
                DeliveryScrape.empty()
        );
    }

    private static Double contextBuildLatencySeconds(Connection connection) {
        try {
            return nullableDecimal(connection, """
                    SELECT AVG(EXTRACT(EPOCH FROM (next_at - entered_at)))
                    FROM (
                        SELECT status,
                               entered_at,
                               LEAD(entered_at) OVER (PARTITION BY task_id ORDER BY entered_at, id) AS next_at
                        FROM rd_task_status_events
                    ) timeline
                    WHERE status IN ('MATERIAL_READY', 'CONTEXT_BUILDING')
                      AND next_at IS NOT NULL
                      AND next_at >= entered_at
                    """);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static RetrievalOutcome collectRetrieval(Connection connection) {
        try {
            return new RetrievalOutcome(true, retrievalMetrics(connection));
        } catch (Exception ignored) {
            return new RetrievalOutcome(false, null);
        }
    }

    private static ControlPlaneOutcome collectControlPlane(Connection connection) {
        try {
            return new ControlPlaneOutcome(true, controlPlaneMetrics(connection));
        } catch (Exception ignored) {
            return new ControlPlaneOutcome(false, null);
        }
    }

    private static ProjectionOutcome collectProjection(Connection connection) {
        try {
            return new ProjectionOutcome(true, projectionMetrics(connection));
        } catch (Exception ignored) {
            return new ProjectionOutcome(false, null);
        }
    }

    private static InventoryOutcome collectInventory(Connection connection) {
        try {
            return new InventoryOutcome(true, inventoryMetrics(connection));
        } catch (Exception ignored) {
            return new InventoryOutcome(false, null);
        }
    }

    private record RetrievalOutcome(boolean available, RetrievalMetrics metrics) {
    }

    private record ControlPlaneOutcome(boolean available, ControlPlaneMetrics metrics) {
    }

    private record ProjectionOutcome(boolean available, ProjectionMetrics metrics) {
    }

    private record InventoryOutcome(boolean available, InventoryMetrics metrics) {
    }

    private static long count(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }

    private static double decimal(Connection connection, String sql) throws Exception {
        Double value = nullableDecimal(connection, sql);
        return value == null ? 0D : value;
    }

    private static Double nullableDecimal(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return null;
            }
            double value = resultSet.getDouble(1);
            return resultSet.wasNull() ? null : value;
        }
    }

    private static Map<String, Long> failureCategories(Connection connection) throws Exception {
        Map<String, Long> categories = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(NULLIF(error_category, ''), status) AS category, COUNT(*) AS count
                FROM rd_agent_stage_runs
                WHERE error_category <> '' OR status LIKE 'FAILED%'
                GROUP BY category
                ORDER BY count DESC, category ASC
                LIMIT 8
                """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                categories.put(resultSet.getString("category"), resultSet.getLong("count"));
            }
        }
        return Map.copyOf(categories);
    }

    private static List<StageMetric> stageMetrics(Connection connection) throws Exception {
        List<StageMetric> stages = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT role, status, COUNT(*) AS count
                FROM rd_agent_stage_runs
                GROUP BY role, status
                ORDER BY role ASC, status ASC
                """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                stages.add(new StageMetric(
                        resultSet.getString("role"),
                        resultSet.getString("status"),
                        resultSet.getLong("count")
                ));
            }
        }
        return List.copyOf(stages);
    }

    private static RetrievalMetrics retrievalMetrics(Connection connection) throws Exception {
        Map<String, Long> statuses = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status, COUNT(*) AS count
                FROM rd_rag_retrieval_runs
                GROUP BY status
                ORDER BY status ASC
                """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                statuses.put(resultSet.getString("status"), resultSet.getLong("count"));
            }
        }
        return new RetrievalMetrics(
                statuses,
                decimal(connection, """
                        SELECT COALESCE(AVG(EXTRACT(EPOCH FROM (updated_at - created_at))), 0)
                        FROM rd_rag_retrieval_runs
                        """),
                decimal(connection, """
                        SELECT COALESCE(AVG(duration_ms) / 1000.0, 0)
                        FROM rd_rag_retrieval_steps
                        """),
                count(connection, "SELECT COALESCE(SUM(current_iteration), 0) FROM rd_rag_retrieval_runs"),
                count(connection, "SELECT COUNT(*) FROM rd_rag_retrieval_runs WHERE status = 'SUCCEEDED_DEGRADED'"),
                count(connection, "SELECT COUNT(*) FROM rd_rag_retrieval_runs WHERE error_category = 'SCOPE_VIOLATION'"),
                decimal(connection, """
                        SELECT COALESCE(AVG(LEAST(1.0, length(query_preview)::numeric / NULLIF(context_budget_chars, 0))), 0)
                        FROM rd_rag_retrieval_runs
                        """)
        );
    }

    private static ControlPlaneMetrics controlPlaneMetrics(Connection connection) throws Exception {
        return new ControlPlaneMetrics(
                groupedCounts(connection, "SELECT status, COUNT(*) AS count FROM rd_task_retry_checkpoints GROUP BY status ORDER BY status"),
                groupedCounts(connection, "SELECT status, COUNT(*) AS count FROM rd_ai_review_runs GROUP BY status ORDER BY status"),
                decimal(connection, "SELECT COALESCE(AVG(score), 0) FROM rd_ai_review_runs WHERE score > 0"),
                decimal(connection, """
                        SELECT COALESCE(AVG(EXTRACT(EPOCH FROM (updated_at - created_at))), 0)
                        FROM rd_ai_review_runs
                        """)
        );
    }

    private static ProjectionMetrics projectionMetrics(Connection connection) throws Exception {
        return new ProjectionMetrics(
                groupedCounts(connection, """
                        SELECT status, COUNT(*) AS count
                        FROM knowledge_external_index_outbox
                        GROUP BY status
                        ORDER BY status
                        """),
                groupedCounts(connection, """
                        SELECT projection_status AS status, COUNT(*) AS count
                        FROM knowledge_external_index_bindings
                        GROUP BY projection_status
                        ORDER BY projection_status
                        """),
                count(connection, """
                        SELECT COUNT(*)
                        FROM knowledge_external_index_outbox
                        WHERE status IN ('NEEDS_HUMAN','DEAD_LETTER')
                        """),
                decimal(connection, """
                        SELECT COALESCE(MAX(EXTRACT(EPOCH FROM (now() - created_at))), 0)
                        FROM knowledge_external_index_outbox
                        WHERE status NOT IN ('SUCCEEDED','SUPERSEDED','DEAD_LETTER')
                        """)
        );
    }

    private static InventoryMetrics inventoryMetrics(Connection connection) throws Exception {
        Map<String, Long> categories = groupedCounts(connection, """
                SELECT classified.category AS status, COUNT(*) AS count
                  FROM (
                        SELECT CASE
                                 WHEN d.deleted_at IS NOT NULL THEN 'TOMBSTONE'
                                 WHEN d.superseded_by_document_id IS NOT NULL THEN 'SUPERSEDED'
                                 WHEN d.deleted_at IS NULL
                                  AND d.superseded_by_document_id IS NULL
                                  AND d.source_identity_key IS NOT NULL
                                  AND dup.cnt > 1 THEN 'DUPLICATE_UNRESOLVED'
                                 WHEN kb.lifecycle_status IS DISTINCT FROM 'ACTIVE' THEN 'EXCLUDED_BASE_INACTIVE'
                                 WHEN d.local_only_override = TRUE THEN 'EXCLUDED_LOCAL_ONLY'
                                 WHEN d.chunk_count = 0 OR d.checksum IS NULL OR d.checksum = '' THEN 'EXCLUDED_EMPTY'
                                 WHEN b.projection_status IN ('FAILED', 'DEAD_LETTER', 'NEEDS_HUMAN') THEN 'FAILED'
                                 WHEN b.projection_status = 'IN_SYNC' THEN 'IN_SYNC'
                                 WHEN b.document_id IS NOT NULL THEN 'PROJECTING'
                                 ELSE 'PENDING_BACKFILL'
                               END AS category
                          FROM knowledge_documents d
                          JOIN knowledge_bases kb ON kb.id = d.knowledge_base_id
                          LEFT JOIN knowledge_external_index_bindings b
                            ON b.document_id = d.id AND b.provider = 'OPENVIKING'
                          LEFT JOIN (
                                SELECT knowledge_base_id, source_identity_key, COUNT(*) AS cnt
                                  FROM knowledge_documents
                                 WHERE deleted_at IS NULL
                                   AND superseded_by_document_id IS NULL
                                   AND source_identity_key IS NOT NULL
                                 GROUP BY knowledge_base_id, source_identity_key
                          ) dup ON dup.knowledge_base_id = d.knowledge_base_id
                               AND dup.source_identity_key = d.source_identity_key
                  ) classified
                 GROUP BY classified.category
                """);
        return new InventoryMetrics(
                categories,
                categories.getOrDefault("PENDING_BACKFILL", 0L)
        );
    }

    private static Map<String, Long> groupedCounts(Connection connection, String sql) throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                counts.put(resultSet.getString("status"), resultSet.getLong("count"));
            }
        }
        return Map.copyOf(counts);
    }

    private static List<StageMetric> stageMetricsWithDefaultRoles(List<StageMetric> metrics) {
        List<StageMetric> result = new ArrayList<>(metrics == null ? List.of() : metrics);
        for (String role : DELIVERY_ROLES) {
            boolean present = result.stream().anyMatch(metric -> role.equals(metric.role()));
            if (!present) {
                result.add(new StageMetric(role, "NO_DATA", 0));
            }
        }
        return List.copyOf(result);
    }

    private static void appendOptionalGauge(StringBuilder metrics, String name, Double value) {
        if (value == null || value.isNaN()) {
            return;
        }
        metrics.append(name).append(' ').append(format(value)).append('\n');
    }

    private static Double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0D;
        }
        return Math.max(numerator, 0) * 1D / denominator;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String label(String value) {
        return value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ")
                .strip();
    }

    record MetricsSnapshot(
            long totalTaskCount,
            long successTaskCount,
            long validationTotalCount,
            long validationPassedCount,
            long humanInterventionTaskCount,
            long prCreatedTaskCount,
            long retryCount,
            double meanTimeToRepairSeconds,
            Map<String, Long> failureCategories,
            List<StageMetric> stageMetrics,
            RetrievalMetrics retrievalMetrics,
            ControlPlaneMetrics controlPlaneMetrics,
            ProjectionMetrics projectionMetrics,
            InventoryMetrics inventoryMetrics,
            CollectionFlags flags,
            DeliveryScrape deliveryScrape
    ) {
        MetricsSnapshot(
                long totalTaskCount,
                long successTaskCount,
                long validationTotalCount,
                long validationPassedCount,
                long humanInterventionTaskCount,
                long prCreatedTaskCount,
                long retryCount,
                double meanTimeToRepairSeconds,
                Map<String, Long> failureCategories,
                List<StageMetric> stageMetrics
        ) {
            this(
                    totalTaskCount, successTaskCount, validationTotalCount, validationPassedCount,
                    humanInterventionTaskCount, prCreatedTaskCount, retryCount, meanTimeToRepairSeconds,
                    failureCategories, stageMetrics, RetrievalMetrics.empty(), ControlPlaneMetrics.empty(),
                    ProjectionMetrics.empty(), InventoryMetrics.empty()
            );
        }

        MetricsSnapshot(
                long totalTaskCount,
                long successTaskCount,
                long validationTotalCount,
                long validationPassedCount,
                long humanInterventionTaskCount,
                long prCreatedTaskCount,
                long retryCount,
                double meanTimeToRepairSeconds,
                Map<String, Long> failureCategories,
                List<StageMetric> stageMetrics,
                RetrievalMetrics retrievalMetrics
        ) {
            this(
                    totalTaskCount, successTaskCount, validationTotalCount, validationPassedCount,
                    humanInterventionTaskCount, prCreatedTaskCount, retryCount, meanTimeToRepairSeconds,
                    failureCategories, stageMetrics, retrievalMetrics, ControlPlaneMetrics.empty(),
                    ProjectionMetrics.empty(), InventoryMetrics.empty()
            );
        }

        MetricsSnapshot(
                long totalTaskCount,
                long successTaskCount,
                long validationTotalCount,
                long validationPassedCount,
                long humanInterventionTaskCount,
                long prCreatedTaskCount,
                long retryCount,
                double meanTimeToRepairSeconds,
                Map<String, Long> failureCategories,
                List<StageMetric> stageMetrics,
                RetrievalMetrics retrievalMetrics,
                ControlPlaneMetrics controlPlaneMetrics
        ) {
            this(
                    totalTaskCount, successTaskCount, validationTotalCount, validationPassedCount,
                    humanInterventionTaskCount, prCreatedTaskCount, retryCount, meanTimeToRepairSeconds,
                    failureCategories, stageMetrics, retrievalMetrics, controlPlaneMetrics,
                    ProjectionMetrics.empty(), InventoryMetrics.empty()
            );
        }

        MetricsSnapshot(
                long totalTaskCount,
                long successTaskCount,
                long validationTotalCount,
                long validationPassedCount,
                long humanInterventionTaskCount,
                long prCreatedTaskCount,
                long retryCount,
                double meanTimeToRepairSeconds,
                Map<String, Long> failureCategories,
                List<StageMetric> stageMetrics,
                RetrievalMetrics retrievalMetrics,
                ControlPlaneMetrics controlPlaneMetrics,
                ProjectionMetrics projectionMetrics
        ) {
            this(
                    totalTaskCount, successTaskCount, validationTotalCount, validationPassedCount,
                    humanInterventionTaskCount, prCreatedTaskCount, retryCount, meanTimeToRepairSeconds,
                    failureCategories, stageMetrics, retrievalMetrics, controlPlaneMetrics,
                    projectionMetrics, InventoryMetrics.empty()
            );
        }

        MetricsSnapshot(
                long totalTaskCount,
                long successTaskCount,
                long validationTotalCount,
                long validationPassedCount,
                long humanInterventionTaskCount,
                long prCreatedTaskCount,
                long retryCount,
                double meanTimeToRepairSeconds,
                Map<String, Long> failureCategories,
                List<StageMetric> stageMetrics,
                RetrievalMetrics retrievalMetrics,
                ControlPlaneMetrics controlPlaneMetrics,
                ProjectionMetrics projectionMetrics,
                InventoryMetrics inventoryMetrics
        ) {
            this(
                    totalTaskCount, successTaskCount, validationTotalCount, validationPassedCount,
                    humanInterventionTaskCount, prCreatedTaskCount, retryCount, meanTimeToRepairSeconds,
                    failureCategories, stageMetrics, retrievalMetrics, controlPlaneMetrics,
                    projectionMetrics, inventoryMetrics, CollectionFlags.healthy(), DeliveryScrape.empty()
            );
        }

        MetricsSnapshot(
                long totalTaskCount,
                long successTaskCount,
                long validationTotalCount,
                long validationPassedCount,
                long humanInterventionTaskCount,
                long prCreatedTaskCount,
                long retryCount,
                double meanTimeToRepairSeconds,
                Map<String, Long> failureCategories,
                List<StageMetric> stageMetrics,
                RetrievalMetrics retrievalMetrics,
                ControlPlaneMetrics controlPlaneMetrics,
                ProjectionMetrics projectionMetrics,
                InventoryMetrics inventoryMetrics,
                CollectionFlags flags
        ) {
            this(
                    totalTaskCount, successTaskCount, validationTotalCount, validationPassedCount,
                    humanInterventionTaskCount, prCreatedTaskCount, retryCount, meanTimeToRepairSeconds,
                    failureCategories, stageMetrics, retrievalMetrics, controlPlaneMetrics,
                    projectionMetrics, inventoryMetrics, flags, DeliveryScrape.empty()
            );
        }

        MetricsSnapshot {
            failureCategories = failureCategories == null ? Map.of() : Map.copyOf(failureCategories);
            stageMetrics = stageMetrics == null ? List.of() : List.copyOf(stageMetrics);
            retrievalMetrics = retrievalMetrics == null ? RetrievalMetrics.empty() : retrievalMetrics;
            controlPlaneMetrics = controlPlaneMetrics == null ? ControlPlaneMetrics.empty() : controlPlaneMetrics;
            projectionMetrics = projectionMetrics == null ? ProjectionMetrics.empty() : projectionMetrics;
            inventoryMetrics = inventoryMetrics == null ? InventoryMetrics.empty() : inventoryMetrics;
            flags = flags == null ? CollectionFlags.healthy() : flags;
            deliveryScrape = deliveryScrape == null ? DeliveryScrape.empty() : deliveryScrape;
        }

        static MetricsSnapshot empty() {
            return new MetricsSnapshot(0, 0, 0, 0, 0, 0, 0, 0D, Map.of(), List.of(),
                    RetrievalMetrics.empty(), ControlPlaneMetrics.empty(), ProjectionMetrics.empty(),
                    InventoryMetrics.empty());
        }

        static MetricsSnapshot collectionFailed() {
            return new MetricsSnapshot(0, 0, 0, 0, 0, 0, 0, 0D, Map.of(), List.of(),
                    RetrievalMetrics.empty(), ControlPlaneMetrics.empty(), ProjectionMetrics.empty(),
                    InventoryMetrics.empty(), CollectionFlags.failed());
        }
    }

    /**
     * Per-collector scrape health. Empty/null flags default to healthy so existing render tests
     * keep emitting business series.
     */
    record CollectionFlags(
            boolean deliveryAvailable,
            boolean retrievalAvailable,
            boolean controlPlaneAvailable,
            boolean projectionAvailable,
            boolean inventoryAvailable,
            Double contextBuildLatencySeconds,
            long generatedAtUnix,
            double scrapeDurationSeconds,
            long invalidObservations,
            boolean cacheFresh
    ) {
        CollectionFlags(
                boolean deliveryAvailable,
                boolean retrievalAvailable,
                boolean controlPlaneAvailable,
                boolean projectionAvailable,
                boolean inventoryAvailable,
                Double contextBuildLatencySeconds,
                long generatedAtUnix
        ) {
            this(
                    deliveryAvailable, retrievalAvailable, controlPlaneAvailable, projectionAvailable,
                    inventoryAvailable, contextBuildLatencySeconds, generatedAtUnix,
                    0D, 0L, deliveryAvailable
            );
        }

        static CollectionFlags healthy() {
            return new CollectionFlags(true, true, true, true, true, null, System.currentTimeMillis() / 1000L);
        }

        static CollectionFlags failed() {
            return new CollectionFlags(false, false, false, false, false, null, 0L);
        }
    }

    record DeliveryContribution(
            boolean fromQueryService,
            boolean available,
            boolean cacheFresh,
            long totalTasks,
            long successTasks,
            long validationTotal,
            long validationPassed,
            long humanInterventionTasks,
            long prCreatedTasks,
            long retryCount,
            double meanTimeToRepairSeconds,
            Map<String, Long> failureCategories,
            List<StageMetric> stageMetrics,
            Double contextLatencySeconds,
            long generatedAtUnix,
            long invalidObservations,
            DeliveryScrape scrape
    ) {
        static DeliveryContribution sqlPlaceholder() {
            return new DeliveryContribution(
                    false, false, true, 0, 0, 0, 0, 0, 0, 0, 0D, Map.of(), List.of(),
                    null, 0L, 0L, DeliveryScrape.empty());
        }
    }

    /**
     * Query-service backed delivery series. When {@code present} is false the renderer
     * falls back to the legacy snapshot counts.
     */
    record DeliveryScrape(
            boolean present,
            long accepted,
            long running,
            long success,
            long failure,
            long cancelled,
            Double successRate,
            Double qaPassRate,
            Double prCreationRate,
            Double humanInterventionRate,
            Double retryRate,
            Double meanEndToEndSeconds,
            Double contextLatencySeconds,
            long queueBacklog,
            double oldestQueueAgeSeconds,
            List<CapacityMetric> capacities,
            SchedulerLiveMetrics scheduler,
            Map<String, DurationHistogram> durationHistograms,
            List<UsageBucket> usageBuckets,
            List<StageMetric> stageMetrics,
            Map<String, Long> failureCategories,
            long invalidObservations
    ) {
        DeliveryScrape {
            capacities = capacities == null ? List.of() : List.copyOf(capacities);
            scheduler = scheduler == null ? SchedulerLiveMetrics.unavailable() : scheduler;
            durationHistograms = durationHistograms == null ? Map.of() : Map.copyOf(durationHistograms);
            usageBuckets = usageBuckets == null ? List.of() : List.copyOf(usageBuckets);
            stageMetrics = stageMetrics == null ? List.of() : List.copyOf(stageMetrics);
            failureCategories = failureCategories == null ? Map.of() : Map.copyOf(failureCategories);
        }

        static DeliveryScrape empty() {
            return new DeliveryScrape(
                    false, 0L, 0L, 0L, 0L, 0L,
                    null, null, null, null, null, null, null,
                    0L, Double.NaN, List.of(), SchedulerLiveMetrics.unavailable(),
                    Map.of(), List.of(), List.of(), Map.of(), 0L);
        }

        static DeliveryScrape fromLegacy(MetricsSnapshot snapshot) {
            MetricsSnapshot safe = snapshot == null ? MetricsSnapshot.empty() : snapshot;
            return new DeliveryScrape(
                    true,
                    Math.max(safe.totalTaskCount(), 0L),
                    0L,
                    Math.max(safe.successTaskCount(), 0L),
                    Math.max(safe.humanInterventionTaskCount(), 0L),
                    0L,
                    null, null, null, null, null,
                    safe.meanTimeToRepairSeconds(),
                    safe.flags() == null ? null : safe.flags().contextBuildLatencySeconds(),
                    0L,
                    Double.NaN,
                    List.of(),
                    SchedulerLiveMetrics.unavailable(),
                    Map.of("end_to_end", DurationHistogram.empty()),
                    List.of(),
                    safe.stageMetrics(),
                    safe.failureCategories(),
                    0L
            );
        }

        static DeliveryScrape from(DeliveryObservabilityOverview overview) {
            if (overview == null || !overview.successRate().available()) {
                return empty();
            }
            long success = overview.successRate().numerator();
            long judged = overview.successRate().denominator();
            long failure = Math.max(0L, judged - success);
            long cancelled = Math.max(0L, overview.terminalCount() - judged);
            PercentileMetric context = overview.phaseLatency().get("context");
            List<StageMetric> stages = overview.stageTotals().stream()
                    .map(item -> new StageMetric(item.role(), item.status(), item.count()))
                    .toList();
            return new DeliveryScrape(
                    true,
                    overview.acceptedCount(),
                    overview.runningCount(),
                    success,
                    failure,
                    cancelled,
                    ratioValue(overview.successRate()),
                    ratioValue(overview.qaPassRate()),
                    ratioValue(overview.prCreationRate()),
                    ratioValue(overview.humanInterventionRate()),
                    ratioValue(overview.retryRate()),
                    percentileMean(overview.endToEndLatency()),
                    percentileMean(context),
                    overview.queueBacklog(),
                    overview.oldestQueueAgeSeconds(),
                    overview.capacities(),
                    overview.schedulerLive(),
                    overview.durationHistograms(),
                    overview.usageBuckets(),
                    stages,
                    overview.failureCategories(),
                    overview.invalidObservations()
            );
        }

        private static Double ratioValue(RatioMetric ratio) {
            if (ratio == null || !ratio.available() || ratio.noSample()) {
                return null;
            }
            return ratio.value();
        }

        private static Double percentileMean(PercentileMetric metric) {
            if (metric == null || !metric.available() || metric.noSample() || Double.isNaN(metric.meanSeconds())) {
                return null;
            }
            return metric.meanSeconds();
        }
    }

    record StageMetric(String role, String status, long count) {
        StageMetric {
            role = label(role);
            status = label(status);
            count = Math.max(count, 0);
        }
    }

    record RetrievalMetrics(
            Map<String, Long> runStatuses,
            double meanRunDurationSeconds,
            double meanStepDurationSeconds,
            long iterationTotal,
            long degradedTotal,
            long scopeViolationTotal,
            double contextBudgetRatio
    ) {
        RetrievalMetrics {
            runStatuses = runStatuses == null ? Map.of() : Map.copyOf(runStatuses);
            meanRunDurationSeconds = Math.max(meanRunDurationSeconds, 0D);
            meanStepDurationSeconds = Math.max(meanStepDurationSeconds, 0D);
            iterationTotal = Math.max(iterationTotal, 0L);
            degradedTotal = Math.max(degradedTotal, 0L);
            scopeViolationTotal = Math.max(scopeViolationTotal, 0L);
            contextBudgetRatio = Math.max(0D, Math.min(contextBudgetRatio, 1D));
        }

        static RetrievalMetrics empty() {
            return new RetrievalMetrics(Map.of(), 0D, 0D, 0L, 0L, 0L, 0D);
        }
    }

    record ControlPlaneMetrics(
            Map<String, Long> retryStatuses,
            Map<String, Long> aiReviewStatuses,
            double meanAiReviewScore,
            double meanAiReviewDurationSeconds
    ) {
        ControlPlaneMetrics {
            retryStatuses = retryStatuses == null ? Map.of() : Map.copyOf(retryStatuses);
            aiReviewStatuses = aiReviewStatuses == null ? Map.of() : Map.copyOf(aiReviewStatuses);
            meanAiReviewScore = Math.max(meanAiReviewScore, 0D);
            meanAiReviewDurationSeconds = Math.max(meanAiReviewDurationSeconds, 0D);
        }

        static ControlPlaneMetrics empty() {
            return new ControlPlaneMetrics(Map.of(), Map.of(), 0D, 0D);
        }
    }

    /**
     * 外部索引投影的健康度。计数直接来自 outbox/binding 账本而非进程内计数器，
     * 因此重启和多实例都不会丢失，读到的就是当前真实积压。
     */
    record ProjectionMetrics(
            Map<String, Long> outboxStatuses,
            Map<String, Long> bindingStatuses,
            long stuckTotal,
            double oldestPendingSeconds
    ) {
        ProjectionMetrics {
            outboxStatuses = outboxStatuses == null ? Map.of() : Map.copyOf(outboxStatuses);
            bindingStatuses = bindingStatuses == null ? Map.of() : Map.copyOf(bindingStatuses);
            stuckTotal = Math.max(stuckTotal, 0L);
            oldestPendingSeconds = Math.max(oldestPendingSeconds, 0D);
        }

        static ProjectionMetrics empty() {
            return new ProjectionMetrics(Map.of(), Map.of(), 0L, 0D);
        }
    }

    /**
     * 存量审计分类与待回填剩余。计数来自 SQL 账本，禁止进程内计数器。
     */
    record InventoryMetrics(Map<String, Long> categoryCounts, long pendingBackfill) {
        InventoryMetrics {
            categoryCounts = categoryCounts == null ? Map.of() : Map.copyOf(categoryCounts);
            pendingBackfill = Math.max(pendingBackfill, 0L);
        }

        static InventoryMetrics empty() {
            return new InventoryMetrics(Map.of(), 0L);
        }
    }
}
