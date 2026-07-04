package com.wish.rd.bootstrap.controller.observability;

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
        return metrics.toString();
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
                    stageMetrics(connection)
            );
        } catch (Exception ignored) {
            return MetricsSnapshot.empty();
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
            List<StageMetric> stageMetrics
    ) {
        MetricsSnapshot {
            failureCategories = failureCategories == null ? Map.of() : Map.copyOf(failureCategories);
            stageMetrics = stageMetrics == null ? List.of() : List.copyOf(stageMetrics);
        }

        static MetricsSnapshot empty() {
            return new MetricsSnapshot(0, 0, 0, 0, 0, 0, 0, 0D, Map.of(), List.of());
        }
    }

    record StageMetric(String role, String status, long count) {
        StageMetric {
            role = label(role);
            status = label(status);
            count = Math.max(count, 0);
        }
    }
}
