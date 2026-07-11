package com.wish.rd.bootstrap.controller.admin.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.rag.project.alert.RdProjectAlertConfigService;
import com.wish.rd.rag.project.alert.RdProjectAlertConfigStore;
import com.wish.rd.rag.project.alert.model.RdProjectAlertConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RdProjectAlertConfigControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        RdProjectAlertConfigService service = new RdProjectAlertConfigService(new InMemoryStore());
        mockMvc = MockMvcBuilders.standaloneSetup(new RdProjectAlertConfigController(service)).build();
    }

    @Test
    void shouldGetDefaultAndUpdateProjectAlertConfig() throws Exception {
        String projectId = "7482000000000000101";

        mockMvc.perform(get("/admin/projects/{projectId}/alert-config", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(false)))
                .andExpect(jsonPath("$.failureThreshold", is(1)));

        mockMvc.perform(put("/admin/projects/{projectId}/alert-config", projectId)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "recipients", java.util.List.of(
                                        Map.of("type", "CHAT_ID", "value", "oc_chat"),
                                        Map.of("type", "OPEN_ID", "value", "ou_user")
                                ),
                                "eventTypes", java.util.List.of("TASK_COMPLETED", "TASK_BLOCKED"),
                                "budgetThresholdCny", 36,
                                "failureThreshold", 2
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients", hasSize(2)))
                .andExpect(jsonPath("$.eventTypes", hasSize(2)))
                .andExpect(jsonPath("$.budgetThresholdCny", is(36)))
                .andExpect(jsonPath("$.failureThreshold", is(2)));
    }

    @Test
    void shouldAllowEmptyRecipientsForEnabledProjectAlert() throws Exception {
        mockMvc.perform(put("/admin/projects/{projectId}/alert-config", "7482000000000000103")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "recipients", java.util.List.of(),
                                "eventTypes", java.util.List.of("BUDGET_EXCEEDED"),
                                "budgetThresholdCny", 36,
                                "failureThreshold", 2
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients", hasSize(0)))
                .andExpect(jsonPath("$.budgetThresholdCny", is(36)));
    }

    @Test
    void shouldRejectNonPositiveFailureThreshold() throws Exception {
        mockMvc.perform(put("/admin/projects/{projectId}/alert-config", "7482000000000000102")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "recipients", java.util.List.of(),
                                "eventTypes", java.util.List.of(),
                                "budgetThresholdCny", 0,
                                "failureThreshold", 0
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("failureThreshold must be positive")));
    }

    private static final class InMemoryStore implements RdProjectAlertConfigStore {
        private RdProjectAlertConfig config;

        @Override
        public RdProjectAlertConfig save(RdProjectAlertConfig config) {
            this.config = config;
            return config;
        }

        @Override
        public Optional<RdProjectAlertConfig> findByProjectId(String projectId) {
            return config == null || !config.projectId().equals(projectId) ? Optional.empty() : Optional.of(config);
        }
    }
}
