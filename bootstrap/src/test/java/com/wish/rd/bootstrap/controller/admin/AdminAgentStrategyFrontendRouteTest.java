package com.wish.rd.bootstrap.controller.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminAgentStrategyFrontendRouteTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminFrontendController()).build();
    }

    @Test
    void agentStrategySpaRouteServesAdminIndexAndDoesNotCaptureNestedStrategyApi() throws Exception {
        mockMvc.perform(get("/admin/projects/7494602743903031296/agent-strategy"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"root\"")));
        mockMvc.perform(get("/admin/projects/7494602743903031296/agent-strategies"))
                .andExpect(status().isNotFound());
    }
}
