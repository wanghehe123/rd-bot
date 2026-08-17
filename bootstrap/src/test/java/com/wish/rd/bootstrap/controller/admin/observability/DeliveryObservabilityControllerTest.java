package com.wish.rd.bootstrap.controller.admin.observability;

import com.wish.rd.engine.admin.observability.DeliveryObservabilityQueryService;
import com.wish.rd.engine.admin.observability.DeliveryObservabilitySnapshotPort;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot.StatusEventObservation;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot.StageObservation;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot.TaskObservation;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityQuery;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilitySettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeliveryObservabilityControllerTest {

    private MockMvc mockMvc;
    private AtomicInteger ledgerLoads;

    @BeforeEach
    void setUp() {
        ledgerLoads = new AtomicInteger();
        Instant now = Instant.now();
        DeliveryObservabilityQueryService service = new DeliveryObservabilityQueryService(
                new RecordingPort(now, ledgerLoads),
                DeliveryObservabilitySettings.defaults()
        );
        mockMvc = MockMvcBuilders.standaloneSetup(new DeliveryObservabilityController(service)).build();
    }

    @Test
    void overviewAllowsBlankProjectAndConcreteProject() throws Exception {
        mockMvc.perform(get("/admin/observability/delivery/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount", is(1)))
                .andExpect(jsonPath("$.successRate.numerator", is(1)))
                .andExpect(jsonPath("$.successRate.denominator", is(1)))
                .andExpect(jsonPath("$.dataQuality[0].source", is("delivery_ledger")));

        mockMvc.perform(get("/admin/observability/delivery/overview").param("projectId", "project-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId", is("project-a")))
                .andExpect(jsonPath("$.acceptedCount", is(1)));

        mockMvc.perform(get("/admin/observability/delivery/overview").param("projectId", "project-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount", is(0)));
    }

    @Test
    void rejectAllSentinelUnknownWindowAndHugePageWithoutQueryingLedger() throws Exception {
        int before = ledgerLoads.get();
        mockMvc.perform(get("/admin/observability/delivery/overview").param("projectId", "all"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("projectId all is not a real project id")));
        mockMvc.perform(get("/admin/observability/delivery/timeseries").param("window", "2h"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isString());
        mockMvc.perform(get("/admin/observability/delivery/failures")
                        .param("page", "1")
                        .param("pageSize", "500"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/admin/observability/delivery/tasks").param("role", "NOT_A_ROLE"))
                .andExpect(status().isBadRequest());
        assertEquals(before, ledgerLoads.get(), "illegal queries must not hit the ledger");
    }

    @Test
    void timeseriesIsBoundedAndFailuresTasksAreStable() throws Exception {
        mockMvc.perform(get("/admin/observability/delivery/timeseries").param("window", "24h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points", hasSize(lessThanOrEqualTo(48))))
                .andExpect(jsonPath("$.bucketSeconds").isNumber());

        mockMvc.perform(get("/admin/observability/delivery/failures").param("page", "1").param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page", is(1)))
                .andExpect(jsonPath("$.items").isArray());

        mockMvc.perform(get("/admin/observability/delivery/tasks").param("page", "1").param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].taskId", is("1001")))
                .andExpect(jsonPath("$.items[0].tracePath", startsWith("/admin/traces/")))
                .andExpect(jsonPath("$.items[0].taskPath", startsWith("/admin/rd-tasks/")));
    }

    @Test
    void taskRowsOmitPromptRawEventsAndErrorText() throws Exception {
        String body = mockMvc.perform(get("/admin/observability/delivery/tasks"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertFalse(body.contains("\"prompt\""));
        assertFalse(body.contains("rawEvent"));
        assertFalse(body.contains("agentEvents"));
        assertFalse(body.contains("providerAttemptsJson"));
        assertFalse(body.contains("sk-"));
        assertTrue(body.contains("\"taskId\":\"1001\""));
        assertTrue(body.contains("\"tracePath\":\"/admin/traces/1001\""));
    }

    private static final class RecordingPort implements DeliveryObservabilitySnapshotPort {
        private final Instant generatedAt;
        private final AtomicInteger loads;

        private RecordingPort(Instant generatedAt, AtomicInteger loads) {
            this.generatedAt = generatedAt;
            this.loads = loads;
        }

        @Override
        public DeliveryLedgerSnapshot loadLedger(DeliveryObservabilityQuery query) {
            loads.incrementAndGet();
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
            return new DeliveryLedgerSnapshot(true, generatedAt, "", List.of(task), List.of());
        }
    }
}
