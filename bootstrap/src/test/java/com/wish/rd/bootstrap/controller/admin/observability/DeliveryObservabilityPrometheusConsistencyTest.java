package com.wish.rd.bootstrap.controller.admin.observability;

import com.wish.rd.bootstrap.controller.observability.PrometheusMetricsController;
import com.wish.rd.engine.admin.observability.DeliveryObservabilityQueryService;
import com.wish.rd.engine.admin.observability.DeliveryObservabilitySnapshotPort;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot.CommandObservation;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot.StageObservation;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot.StatusEventObservation;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot.TaskObservation;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityOverview;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityQuery;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilitySettings;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeliveryObservabilityPrometheusConsistencyTest {

    @Test
    void apiAndPrometheusShareSuccessRateTerminalCountStageSamplesBacklogAndHealth() throws Exception {
        Instant now = Instant.now();
        DeliveryObservabilityQueryService queryService = new DeliveryObservabilityQueryService(
                new SharedPort(now),
                DeliveryObservabilitySettings.defaults()
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DeliveryObservabilityController(queryService)).build();
        DeliveryObservabilityOverview overview = queryService.overview(
                DeliveryObservabilityQuery.overview("", "24h", now), now);

        mockMvc.perform(get("/admin/observability/delivery/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value((int) overview.acceptedCount()))
                .andExpect(jsonPath("$.terminalCount").value((int) overview.terminalCount()))
                .andExpect(jsonPath("$.successRate.numerator").value((int) overview.successRate().numerator()))
                .andExpect(jsonPath("$.successRate.denominator").value((int) overview.successRate().denominator()))
                .andExpect(jsonPath("$.queueBacklog").value((int) overview.queueBacklog()))
                .andExpect(jsonPath("$.dataQuality[0].available").value(true));

        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            PreparedStatement statement = mock(PreparedStatement.class);
            ResultSet resultSet = mock(ResultSet.class);
            when(statement.executeQuery()).thenReturn(resultSet);
            when(resultSet.next()).thenReturn(false);
            return statement;
        });
        String metrics = new PrometheusMetricsController(dataSource, queryService).prometheus();

        assertTrue(metrics.contains("rd_bot_repair_success_rate 1.000000"));
        assertTrue(metrics.contains("rd_bot_delivery_completed_total{outcome=\"success\"} "
                + overview.successRate().numerator()));
        assertTrue(metrics.contains("rd_bot_delivery_accepted_total " + overview.acceptedCount()));
        assertTrue(metrics.contains("rd_bot_delivery_queue_backlog{status=\"WAITING\",resource=\"GENERIC\"} "
                + overview.queueBacklog()));
        assertTrue(metrics.contains("rd_bot_agent_stage_total{role=\"QA_AGENT\",status=\"SUCCEEDED\"} 1"));
        assertTrue(metrics.contains("rd_bot_observability_collection_success{source=\"delivery_ledger\"} 1"));
        assertEquals(1L, overview.queueBacklog());
        assertEquals(1L, overview.stageTotals().stream()
                .filter(item -> "QA_AGENT".equals(item.role()) && "SUCCEEDED".equals(item.status()))
                .mapToLong(item -> item.count())
                .sum());
    }

    private static final class SharedPort implements DeliveryObservabilitySnapshotPort {
        private final Instant generatedAt;

        private SharedPort(Instant generatedAt) {
            this.generatedAt = generatedAt;
        }

        @Override
        public DeliveryLedgerSnapshot loadLedger(DeliveryObservabilityQuery query) {
            Instant terminal = generatedAt.minusSeconds(60);
            Instant accepted = terminal.minusSeconds(3600);
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
                            new StatusEventObservation("COMPLETED", terminal, 0L)
                    ),
                    List.of(new StageObservation(
                            "qa-1", "QA_AGENT", "SUCCEEDED", 1,
                            accepted.plusSeconds(100), terminal.minusSeconds(10), ""
                    ))
            );
            return new DeliveryLedgerSnapshot(
                    true,
                    generatedAt,
                    "",
                    List.of(task),
                    List.of(new CommandObservation("cmd-1", "PENDING", "GENERIC", generatedAt.minusSeconds(90), true))
            );
        }
    }
}
