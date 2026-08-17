package com.wish.rd.bootstrap.controller.observability;

import com.wish.rd.engine.admin.observability.DeliveryObservabilityQueryService;
import com.wish.rd.engine.admin.observability.DeliveryObservabilitySnapshotPort;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot.StatusEventObservation;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot.StageObservation;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot.TaskObservation;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityQuery;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilitySettings;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrometheusMetricsControllerTest {

    @Test
    void shouldRenderProductionAcceptanceMetricsAndFourRoleStageSeries() {
        PrometheusMetricsController.MetricsSnapshot snapshot = new PrometheusMetricsController.MetricsSnapshot(
                10,
                8,
                7,
                1,
                2,
                4,
                1,
                360,
                Map.of("QA_AGENT_FAILED", 1L),
                List.of(
                        new PrometheusMetricsController.StageMetric("REQUIREMENT_REVIEWER", "SUCCEEDED", 1),
                        new PrometheusMetricsController.StageMetric("SOLUTION_ARCHITECT", "SUCCEEDED", 1),
                        new PrometheusMetricsController.StageMetric("CODING_AGENT", "SUCCEEDED", 1),
                        new PrometheusMetricsController.StageMetric("QA_AGENT", "SUCCEEDED", 1)
                )
        );

        String metrics = PrometheusMetricsController.render(snapshot);

        assertTrue(metrics.contains("rd_bot_context_build_latency"));
        assertTrue(metrics.contains("rd_bot_repair_success_rate"));
        assertTrue(metrics.contains("rd_bot_validation_pass_rate"));
        assertTrue(metrics.contains("rd_bot_pr_creation_rate"));
        assertTrue(metrics.contains("rd_bot_human_intervention_rate"));
        assertTrue(metrics.contains("rd_bot_retry_rate"));
        assertTrue(metrics.contains("rd_bot_mean_time_to_repair"));
        assertTrue(metrics.contains("rd_bot_top_failure_categories"));
        assertTrue(metrics.lines().filter(line -> line.contains("rd_bot_agent_stage")).count() >= 4);
        assertTrue(metrics.contains("rd_bot_rag_run_total"));
        assertTrue(metrics.contains("rd_bot_rag_run_duration_seconds"));
        assertTrue(metrics.contains("rd_bot_rag_step_duration_seconds"));
        assertTrue(metrics.contains("rd_bot_rag_iteration_total"));
        assertTrue(metrics.contains("rd_bot_rag_degraded_total"));
        assertTrue(metrics.contains("rd_bot_rag_scope_violation_total"));
        assertTrue(metrics.contains("rd_bot_rag_context_budget_ratio"));
        assertTrue(metrics.contains("# HELP rd_bot_observability_collection_success"));
        assertTrue(metrics.contains("# TYPE rd_bot_observability_collection_success gauge"));
        assertTrue(metrics.contains("rd_bot_delivery_completed_total{outcome=\"success\"}"));
        assertTrue(metrics.contains("rd_bot_delivery_duration_seconds_bucket"));
        assertTrue(metrics.contains("rd_bot_observability_scrape_duration_seconds"));
        assertTrue(metrics.contains("rd_bot_observability_scrape_bytes"));
    }

    @Test
    void shouldKeepLegacyMetricsWhenRetrievalMetricsAreEmpty() {
        PrometheusMetricsController.MetricsSnapshot snapshot = new PrometheusMetricsController.MetricsSnapshot(
                10,
                8,
                7,
                6,
                2,
                4,
                1,
                360,
                Map.of("QA_AGENT_FAILED", 1L),
                List.of(new PrometheusMetricsController.StageMetric("QA_AGENT", "SUCCEEDED", 1)),
                PrometheusMetricsController.RetrievalMetrics.empty(),
                new PrometheusMetricsController.ControlPlaneMetrics(
                        Map.of("SUCCEEDED", 3L),
                        Map.of("SUCCEEDED_OK", 2L, "FAILED_RETRYABLE", 1L),
                        88.5D,
                        12.25D)
        );

        String metrics = PrometheusMetricsController.render(snapshot);

        assertTrue(metrics.contains("rd_bot_repair_success_rate 0.8"));
        assertTrue(metrics.contains("rd_bot_rag_run_total"));
        assertTrue(metrics.contains("rd_bot_task_retry_checkpoint_total{status=\"SUCCEEDED\"} 3"));
        assertTrue(metrics.contains("rd_bot_ai_review_run_total{status=\"SUCCEEDED_OK\"} 2"));
        assertTrue(metrics.contains("rd_bot_ai_review_score 88.500000"));
        assertTrue(metrics.contains("rd_bot_ai_review_duration_seconds 12.250000"));
    }

    /**
     * 投影是异步且默认关闭的，运维唯一能看见"卡住了"的地方就是这几条曲线：
     * 停在 NEEDS_HUMAN/DEAD_LETTER 的行数，以及最老一行未收敛了多久。
     */
    @Test
    void shouldExposeProjectionBacklogAndStuckRows() {
        PrometheusMetricsController.MetricsSnapshot snapshot = new PrometheusMetricsController.MetricsSnapshot(
                1, 1, 1, 1, 0, 1, 0, 1,
                Map.of(),
                List.of(),
                PrometheusMetricsController.RetrievalMetrics.empty(),
                PrometheusMetricsController.ControlPlaneMetrics.empty(),
                new PrometheusMetricsController.ProjectionMetrics(
                        Map.of("PENDING", 2L, "WAITING_REMOTE", 1L, "NEEDS_HUMAN", 3L, "DEAD_LETTER", 1L),
                        Map.of("IN_SYNC", 9L, "NEEDS_HUMAN", 3L),
                        4L,
                        615D)
        );

        String metrics = PrometheusMetricsController.render(snapshot);

        assertTrue(metrics.contains("rd_bot_knowledge_projection_outbox_total{status=\"WAITING_REMOTE\"} 1"));
        assertTrue(metrics.contains("rd_bot_knowledge_projection_binding_total{status=\"IN_SYNC\"} 9"));
        assertTrue(metrics.contains("rd_bot_knowledge_projection_stuck_total 4"));
        assertTrue(metrics.contains("rd_bot_knowledge_projection_oldest_pending_seconds 615.000000"));
    }

    @Test
    void shouldRenderProjectionSeriesEvenBeforeAnyDocumentIsProjected() {
        String metrics = PrometheusMetricsController.render(PrometheusMetricsController.MetricsSnapshot.empty());

        assertTrue(metrics.contains("rd_bot_knowledge_projection_outbox_total{status=\"NO_DATA\"} 0"));
        assertTrue(metrics.contains("rd_bot_knowledge_projection_stuck_total 0"));
    }

    @Test
    void shouldExposeInventoryCategoryGaugesFromTheLedgerSnapshot() {
        PrometheusMetricsController.MetricsSnapshot snapshot = new PrometheusMetricsController.MetricsSnapshot(
                1, 1, 1, 1, 0, 1, 0, 1,
                Map.of(),
                List.of(),
                PrometheusMetricsController.RetrievalMetrics.empty(),
                PrometheusMetricsController.ControlPlaneMetrics.empty(),
                PrometheusMetricsController.ProjectionMetrics.empty(),
                new PrometheusMetricsController.InventoryMetrics(
                        Map.of("PENDING_BACKFILL", 12L, "IN_SYNC", 3L, "TOMBSTONE", 1L),
                        12L)
        );

        String metrics = PrometheusMetricsController.render(snapshot);

        assertTrue(metrics.contains("rd_bot_knowledge_inventory_documents_total{category=\"PENDING_BACKFILL\"} 12"));
        assertTrue(metrics.contains("rd_bot_knowledge_inventory_documents_total{category=\"IN_SYNC\"} 3"));
        assertTrue(metrics.contains("rd_bot_knowledge_inventory_documents_total{category=\"TOMBSTONE\"} 1"));
        assertTrue(metrics.contains("rd_bot_knowledge_inventory_documents_total{category=\"FAILED\"} 0"));
        assertTrue(metrics.contains("rd_bot_knowledge_inventory_backfill_pending 12"));
    }

    @Test
    void shouldRenderInventorySeriesEvenBeforeAnyDocumentExists() {
        String metrics = PrometheusMetricsController.render(PrometheusMetricsController.MetricsSnapshot.empty());

        assertTrue(metrics.contains("rd_bot_knowledge_inventory_documents_total{category=\"PENDING_BACKFILL\"} 0"));
        assertTrue(metrics.contains("rd_bot_knowledge_inventory_backfill_pending 0"));
    }

    @Test
    void contextBuildLatencyMustNotReuseMeanRepairTime() {
        PrometheusMetricsController.MetricsSnapshot snapshot = new PrometheusMetricsController.MetricsSnapshot(
                10, 8, 7, 6, 1, 4, 1, 360D,
                Map.of("QA_AGENT_FAILED", 1L),
                List.of(new PrometheusMetricsController.StageMetric("QA_AGENT", "SUCCEEDED", 1))
        );

        String metrics = PrometheusMetricsController.render(snapshot);
        assertFalse(metrics.toLowerCase().contains("placeholder"),
                "HELP text must not describe context latency as a placeholder");
        assertTrue(metrics.contains("# HELP rd_bot_context_build_latency_seconds"));
        assertTrue(metrics.contains("rd_bot_mean_time_to_repair_seconds 360.000000"));
        assertFalse(metrics.lines().anyMatch(line -> line.startsWith("rd_bot_context_build_latency_seconds ")),
                "context build latency must not copy mean time to repair when context samples are missing");
    }

    @Test
    void topLevelQueryFailureMustExposeCollectorHealthInsteadOfHealthyZeros() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("delivery ledger unavailable"));

        String metrics = new PrometheusMetricsController(dataSource).prometheus();

        assertTrue(metrics.contains("rd_bot_observability_collection_success{source=\"delivery_ledger\"} 0"));
        assertFalse(containsExactGauge(metrics, "rd_bot_repair_success_rate", 0D),
                "a failed collection must not publish a healthy business zero");
        assertFalse(containsExactGauge(metrics, "rd_bot_context_build_latency_seconds", 0D),
                "a failed collection must not publish a healthy context-latency zero");
    }

    @Test
    void subqueryFailureMustNotZeroIndependentHealthyContributors() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql != null && sql.contains("rd_rag_retrieval")) {
                throw new SQLException("retrieval ledger unavailable");
            }
            PreparedStatement statement = mock(PreparedStatement.class);
            ResultSet resultSet = mock(ResultSet.class);
            when(statement.executeQuery()).thenReturn(resultSet);
            when(resultSet.next()).thenReturn(false);
            return statement;
        });

        String metrics = new PrometheusMetricsController(dataSource).prometheus();

        assertTrue(metrics.contains("rd_bot_observability_collection_success{source=\"retrieval_ledger\"} 0"));
        assertFalse(containsExactGauge(metrics, "rd_bot_rag_run_duration_seconds", 0D),
                "a failed retrieval contributor must not look like a healthy zero duration");
        assertTrue(metrics.contains("rd_bot_observability_collection_success{source=\"knowledge_projection\"} 1")
                        || metrics.contains("rd_bot_knowledge_projection_"),
                "independent projection metrics must still be attempted");
    }

    private static double gauge(String metrics, String name) {
        return metrics.lines()
                .filter(line -> line.startsWith(name + " "))
                .map(line -> Double.parseDouble(line.substring(name.length()).strip()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing gauge " + name + " in:\n" + metrics));
    }

    private static boolean containsExactGauge(String metrics, String name, double expected) {
        String needle = name + " " + String.format(java.util.Locale.ROOT, "%.6f", expected);
        return metrics.contains(needle);
    }

    @Test
    void prometheusLabelsStayLowCardinalityAndDurationBucketsAreMonotonic() {
        PrometheusMetricsController.MetricsSnapshot snapshot = new PrometheusMetricsController.MetricsSnapshot(
                10, 8, 7, 1, 2, 4, 1, 360,
                Map.of("TIMEOUT", 1L, "this is a free-text error from provider", 2L),
                List.of(new PrometheusMetricsController.StageMetric("CODING_AGENT", "SUCCEEDED", 1))
        );

        String metrics = PrometheusMetricsController.render(snapshot);

        assertFalse(metrics.contains("taskId="));
        assertFalse(metrics.contains("stageRunId="));
        assertFalse(metrics.contains("projectId="));
        assertFalse(metrics.contains("https://"));
        assertFalse(metrics.contains("this is a free-text error"));
        assertTrue(metrics.contains("rd_bot_top_failure_categories{category=\"TIMEOUT\"}"));
        assertTrue(metrics.contains("rd_bot_top_failure_categories{category=\"UNKNOWN\"}"));
        assertTrue(metrics.contains("# HELP rd_bot_delivery_duration_seconds"));
        assertTrue(metrics.contains("# TYPE rd_bot_delivery_duration_seconds histogram"));

        java.util.ArrayList<Double> buckets = new java.util.ArrayList<>();
        metrics.lines()
                .filter(line -> line.startsWith("rd_bot_delivery_duration_seconds_bucket{phase=\"end_to_end\""))
                .forEach(line -> {
                    int leIndex = line.indexOf(",le=\"");
                    int leEnd = line.indexOf('"', leIndex + 5);
                    String le = line.substring(leIndex + 5, leEnd);
                    buckets.add("+Inf".equals(le) ? Double.POSITIVE_INFINITY : Double.parseDouble(le));
                    long count = Long.parseLong(line.substring(line.lastIndexOf(' ') + 1));
                    assertTrue(count >= 0L);
                });
        assertTrue(buckets.size() >= 16);
        for (int i = 1; i < buckets.size(); i++) {
            assertTrue(buckets.get(i) >= buckets.get(i - 1), "histogram le must be monotonic");
        }
        assertTrue(metrics.contains("rd_bot_observability_cache_fresh"));
    }

    @Test
    void queryServiceSnapshotDoesNotIssueDeliveryCountSqlAndUsesTerminalDenominator() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql != null && sql.contains("SELECT COUNT(*) FROM rd_tasks")) {
                throw new AssertionError("delivery counts must come from the query service, not controller SQL: " + sql);
            }
            if (sql != null && sql.contains("rd_rag_retrieval")) {
                throw new SQLException("retrieval ledger unavailable");
            }
            PreparedStatement statement = mock(PreparedStatement.class);
            ResultSet resultSet = mock(ResultSet.class);
            when(statement.executeQuery()).thenReturn(resultSet);
            when(resultSet.next()).thenReturn(false);
            return statement;
        });

        Instant now = Instant.now();
        DeliveryObservabilityQueryService queryService = new DeliveryObservabilityQueryService(
                new FixedLedgerPort(now),
                DeliveryObservabilitySettings.defaults()
        );

        String metrics = new PrometheusMetricsController(dataSource, queryService).prometheus();

        assertTrue(metrics.contains("rd_bot_repair_success_rate 1.000000"),
                "success rate must use the terminal denominator from the query service:\n" + metrics);
        assertTrue(metrics.contains("rd_bot_delivery_accepted_total 1"));
        assertTrue(metrics.contains("rd_bot_delivery_active{phase=\"running\"} 0"));
        assertTrue(metrics.contains("rd_bot_delivery_completed_total{outcome=\"success\"} 1"));
        assertTrue(metrics.contains("rd_bot_delivery_queue_backlog"));
        assertTrue(metrics.contains("rd_bot_delivery_resource_in_use"));
        assertTrue(metrics.contains("rd_bot_delivery_tokens_total"));
        assertTrue(metrics.contains("Estimated Provider cost in CNY, not a bill."));
        assertTrue(metrics.contains("rd_bot_delivery_duration_seconds_count{phase=\"end_to_end\""));
        assertFalse(metrics.contains("rd_bot_delivery_duration_seconds_count{phase=\"end_to_end\",role=\"ALL\",runtime=\"unknown\"} 0"),
                "fixture e2e sample must populate the duration histogram");
        assertTrue(metrics.contains("rd_bot_observability_collection_success{source=\"retrieval_ledger\"} 0"));
        assertTrue(metrics.contains("rd_bot_knowledge_projection_")
                || metrics.contains("rd_bot_observability_collection_success{source=\"knowledge_projection\"} 1"));
        assertFalse(metrics.contains("taskId="));
        assertFalse(metrics.contains("stageRunId="));
        assertFalse(metrics.contains("projectId="));
    }

    @Test
    void queryServiceSurvivesIndependentDataSourceFailure() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("projection inventory unavailable"));
        Instant now = Instant.now();
        DeliveryObservabilityQueryService queryService = new DeliveryObservabilityQueryService(
                new FixedLedgerPort(now),
                DeliveryObservabilitySettings.defaults()
        );

        String metrics = new PrometheusMetricsController(dataSource, queryService).prometheus();

        assertTrue(metrics.contains("rd_bot_observability_collection_success{source=\"delivery_ledger\"} 1"));
        assertTrue(metrics.contains("rd_bot_repair_success_rate 1.000000"));
        assertTrue(metrics.contains("rd_bot_observability_collection_success{source=\"knowledge_projection\"} 0"));
        assertFalse(containsExactGauge(metrics, "rd_bot_knowledge_projection_oldest_pending_seconds", 0D));
    }

    private static final class FixedLedgerPort implements DeliveryObservabilitySnapshotPort {
        private final Instant generatedAt;

        private FixedLedgerPort(Instant generatedAt) {
            this.generatedAt = generatedAt;
        }

        @Override
        public DeliveryLedgerSnapshot loadLedger(DeliveryObservabilityQuery query) {
            Instant terminal = generatedAt.minusSeconds(60);
            Instant accepted = terminal.minusSeconds(3600);
            Instant context = accepted.plusSeconds(600);
            TaskObservation task = new TaskObservation(
                    "1001",
                    "project-a",
                    "success",
                    "COMPLETED",
                    accepted,
                    terminal,
                    "https://github.example/pr/1",
                    "",
                    List.of(
                            new StatusEventObservation("CREATED", accepted, 0L),
                            new StatusEventObservation("MATERIAL_READY", context.minusSeconds(300), 0L),
                            new StatusEventObservation("CONTEXT_READY", context, 0L),
                            new StatusEventObservation("COMPLETED", terminal, 0L)
                    ),
                    List.of(new StageObservation(
                            "qa-1",
                            "QA_AGENT",
                            "SUCCEEDED",
                            1,
                            accepted.plusSeconds(2400),
                            accepted.plusSeconds(3000),
                            "",
                            List.of(new DeliveryLedgerSnapshot.AttemptUsage(
                                    "attempt-1", "pi", "anthropic", 10, 4, 1, 0.25D, true, 120L, 80L
                            ))
                    ))
            );
            return new DeliveryLedgerSnapshot(true, generatedAt, "", List.of(task), List.of());
        }
    }
}
