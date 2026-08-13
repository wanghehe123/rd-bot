package com.wish.rd.bootstrap.controller.observability;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
