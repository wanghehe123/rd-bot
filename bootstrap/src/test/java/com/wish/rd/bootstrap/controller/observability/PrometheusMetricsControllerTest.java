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
    }
}
