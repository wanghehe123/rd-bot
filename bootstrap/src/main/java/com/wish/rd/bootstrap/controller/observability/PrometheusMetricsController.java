package com.wish.rd.bootstrap.controller.observability;

import com.wish.rd.rag.knowledge.projection.model.InventoryCategory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    public PrometheusMetricsController(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @GetMapping(value = "/actuator/prometheus", produces = MediaType.TEXT_PLAIN_VALUE)
    public String prometheus() {
        return render(snapshot());
    }

    static String render(MetricsSnapshot snapshot) {
        MetricsSnapshot safe = snapshot == null ? MetricsSnapshot.empty() : snapshot;
        long totalTasks = Math.max(safe.totalTaskCount(), 0);
        long successTasks = Math.max(safe.successTaskCount(), 0);
        long validationTotal = Math.max(safe.validationTotalCount(), 0);
        long validationPassed = Math.max(safe.validationPassedCount(), 0);
        long humanInterventionTasks = Math.max(safe.humanInterventionTaskCount(), 0);
        long retryCount = Math.max(safe.retryCount(), 0);
        double repairSuccessRate = ratio(successTasks, totalTasks);
        double validationPassRate = ratio(validationPassed, validationTotal);
        double prCreationRate = ratio(safe.prCreatedTaskCount(), totalTasks);
        double humanInterventionRate = ratio(humanInterventionTasks, totalTasks);
        double retryRate = ratio(retryCount, Math.max(validationTotal, totalTasks));
        StringBuilder metrics = new StringBuilder()
                .append("# HELP rd_bot_context_build_latency_seconds RD-Bot context build latency placeholder from task lifecycle.\n")
                .append("# TYPE rd_bot_context_build_latency_seconds gauge\n")
                .append("rd_bot_context_build_latency_seconds ")
                .append(format(Math.max(safe.meanTimeToRepairSeconds(), 0))).append('\n')
                .append("# HELP rd_bot_repair_success_rate RD-Bot repair success rate.\n")
                .append("# TYPE rd_bot_repair_success_rate gauge\n")
                .append("rd_bot_repair_success_rate ").append(format(repairSuccessRate)).append('\n')
                .append("# HELP rd_bot_validation_pass_rate RD-Bot validation pass rate.\n")
                .append("# TYPE rd_bot_validation_pass_rate gauge\n")
                .append("rd_bot_validation_pass_rate ").append(format(validationPassRate)).append('\n')
                .append("# HELP rd_bot_pr_creation_rate RD-Bot PR creation rate.\n")
                .append("# TYPE rd_bot_pr_creation_rate gauge\n")
                .append("rd_bot_pr_creation_rate ").append(format(prCreationRate)).append('\n')
                .append("# HELP rd_bot_human_intervention_rate RD-Bot human intervention rate.\n")
                .append("# TYPE rd_bot_human_intervention_rate gauge\n")
                .append("rd_bot_human_intervention_rate ").append(format(humanInterventionRate)).append('\n')
                .append("# HELP rd_bot_retry_rate RD-Bot stage retry rate.\n")
                .append("# TYPE rd_bot_retry_rate gauge\n")
                .append("rd_bot_retry_rate ").append(format(retryRate)).append('\n')
                .append("# HELP rd_bot_mean_time_to_repair_seconds RD-Bot mean time to repair.\n")
                .append("# TYPE rd_bot_mean_time_to_repair_seconds gauge\n")
                .append("rd_bot_mean_time_to_repair_seconds ")
                .append(format(Math.max(safe.meanTimeToRepairSeconds(), 0))).append('\n')
                .append("# HELP rd_bot_top_failure_categories RD-Bot top failure categories.\n")
                .append("# TYPE rd_bot_top_failure_categories gauge\n");
        if (safe.failureCategories().isEmpty()) {
            metrics.append("rd_bot_top_failure_categories{category=\"NONE\"} 0\n");
        } else {
            safe.failureCategories().forEach((category, count) -> metrics
                    .append("rd_bot_top_failure_categories{category=\"")
                    .append(label(category))
                    .append("\"} ")
                    .append(Math.max(count, 0))
                    .append('\n'));
        }
        metrics.append("# HELP rd_bot_agent_stage_total RD-Bot agent stage series by role and status.\n")
                .append("# TYPE rd_bot_agent_stage_total gauge\n");
        for (StageMetric stage : stageMetricsWithDefaultRoles(safe.stageMetrics())) {
            metrics.append("rd_bot_agent_stage_total{role=\"")
                    .append(label(stage.role()))
                    .append("\",status=\"")
                    .append(label(stage.status()))
                    .append("\"} ")
                    .append(Math.max(stage.count(), 0))
                    .append('\n');
        }
        appendRetrievalMetrics(metrics, safe.retrievalMetrics());
        appendControlPlaneMetrics(metrics, safe.controlPlaneMetrics());
        appendProjectionMetrics(metrics, safe.projectionMetrics());
        appendInventoryMetrics(metrics, safe.inventoryMetrics());
        return metrics.toString();
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
        try (Connection connection = dataSource.getConnection()) {
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
                            """
            );
            return new MetricsSnapshot(
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
                    safeRetrievalMetrics(connection),
                    safeControlPlaneMetrics(connection),
                    safeProjectionMetrics(connection),
                    safeInventoryMetrics(connection)
            );
        } catch (Exception ignored) {
            return MetricsSnapshot.empty();
        }
    }

    private static RetrievalMetrics safeRetrievalMetrics(Connection connection) {
        try {
            return retrievalMetrics(connection);
        } catch (Exception ignored) {
            return RetrievalMetrics.empty();
        }
    }

    private static ControlPlaneMetrics safeControlPlaneMetrics(Connection connection) {
        try {
            return controlPlaneMetrics(connection);
        } catch (Exception ignored) {
            return ControlPlaneMetrics.empty();
        }
    }

    private static ProjectionMetrics safeProjectionMetrics(Connection connection) {
        try {
            return projectionMetrics(connection);
        } catch (Exception ignored) {
            return ProjectionMetrics.empty();
        }
    }

    private static InventoryMetrics safeInventoryMetrics(Connection connection) {
        try {
            return inventoryMetrics(connection);
        } catch (Exception ignored) {
            return InventoryMetrics.empty();
        }
    }

    private static long count(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }

    private static double decimal(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getDouble(1) : 0D;
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

    private static double ratio(long numerator, long denominator) {
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
            InventoryMetrics inventoryMetrics
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

        MetricsSnapshot {
            failureCategories = failureCategories == null ? Map.of() : Map.copyOf(failureCategories);
            stageMetrics = stageMetrics == null ? List.of() : List.copyOf(stageMetrics);
            retrievalMetrics = retrievalMetrics == null ? RetrievalMetrics.empty() : retrievalMetrics;
            controlPlaneMetrics = controlPlaneMetrics == null ? ControlPlaneMetrics.empty() : controlPlaneMetrics;
            projectionMetrics = projectionMetrics == null ? ProjectionMetrics.empty() : projectionMetrics;
            inventoryMetrics = inventoryMetrics == null ? InventoryMetrics.empty() : inventoryMetrics;
        }

        static MetricsSnapshot empty() {
            return new MetricsSnapshot(0, 0, 0, 0, 0, 0, 0, 0D, Map.of(), List.of(),
                    RetrievalMetrics.empty(), ControlPlaneMetrics.empty(), ProjectionMetrics.empty(),
                    InventoryMetrics.empty());
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
