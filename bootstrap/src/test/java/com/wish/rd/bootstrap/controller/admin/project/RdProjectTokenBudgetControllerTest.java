package com.wish.rd.bootstrap.controller.admin.project;

import com.wish.rd.rag.project.budget.RdProjectTokenBudgetService;
import com.wish.rd.rag.project.budget.RdProjectTokenBudgetStore;
import com.wish.rd.rag.project.budget.model.RdProjectTokenBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.hamcrest.Matchers.is;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RdProjectTokenBudgetControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new RdProjectTokenBudgetController(new RdProjectTokenBudgetService(new InMemoryStore()))
        ).build();
    }

    @Test
    void shouldGetDefaultAndUpdateProjectTokenBudget() throws Exception {
        String projectId = "7482000000000000201";

        mockMvc.perform(get("/admin/projects/{projectId}/token-budget", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultTokenBudget", is(0)));

        mockMvc.perform(put("/admin/projects/{projectId}/token-budget", projectId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"defaultTokenBudget\":120000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultTokenBudget", is(120000)));
    }

    @Test
    void shouldRejectNegativeProjectTokenBudget() throws Exception {
        mockMvc.perform(put("/admin/projects/{projectId}/token-budget", "7482000000000000202")
                        .contentType(APPLICATION_JSON)
                        .content("{\"defaultTokenBudget\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("defaultTokenBudget must not be negative")));
    }

    private static final class InMemoryStore implements RdProjectTokenBudgetStore {
        private RdProjectTokenBudget value;

        @Override
        public RdProjectTokenBudget save(RdProjectTokenBudget budget) {
            value = budget;
            return budget;
        }

        @Override
        public Optional<RdProjectTokenBudget> findByProjectId(String projectId) {
            return value == null || !value.projectId().equals(projectId) ? Optional.empty() : Optional.of(value);
        }
    }
}
