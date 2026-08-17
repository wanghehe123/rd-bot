package com.wish.rd.bootstrap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wish.rd.bootstrap.controller.admin.AdminFrontendController;

class AdminFrontendControllerTest {

    private static final String ADMIN_TITLE = "RD-Bot 管理后台";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminFrontendController())
                .setMessageConverters(new StringHttpMessageConverter(StandardCharsets.UTF_8))
                .defaultResponseCharacterEncoding(StandardCharsets.UTF_8)
                .build();
    }

    @Test
    void servesKnowledgeAdminFrontendRoutesFromSingleSpringBootService() throws Exception {
        mockMvc.perform(get("/admin/knowledge"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(ADMIN_TITLE)))
                .andExpect(content().string(containsString("id=\"root\"")))
                .andExpect(content().string(containsString("admin-knowledge.js")));

        mockMvc.perform(get("/admin/knowledge/kb-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(ADMIN_TITLE)));

        mockMvc.perform(get("/admin/knowledge/kb-1/docs/doc-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(ADMIN_TITLE)));

        mockMvc.perform(get("/admin/knowledge/123/openviking").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(ADMIN_TITLE)))
                .andExpect(content().string(containsString("id=\"root\"")));
    }

    @Test
    void servesUserAdminFrontendRoute() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(ADMIN_TITLE)))
                .andExpect(content().string(containsString("id=\"root\"")))
                .andExpect(content().string(containsString("admin-knowledge.js")));
    }

    @Test
    void servesReactAdminRoutesAndAssets() throws Exception {
        for (String route : new String[]{
                "/admin/dashboard",
                "/admin/traces",
                "/admin/traces/trace-ticket-prompt-flow",
                "/admin/observability",
                "/admin/settings"
        }) {
            mockMvc.perform(get(route))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(ADMIN_TITLE)))
                    .andExpect(content().string(containsString("id=\"root\"")))
                    .andExpect(content().string(containsString("admin-knowledge.css")))
                    .andExpect(content().string(containsString("admin-knowledge.js")));
        }

        mockMvc.perform(get("/admin/sample-questions"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/admin/intent-tree"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/admin/ingestion"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/admin/mappings"))
                .andExpect(status().isNotFound());

        String css = new ClassPathResource("static/admin/admin-knowledge.css")
                .getContentAsString(StandardCharsets.UTF_8);
        String js = new ClassPathResource("static/admin/admin-knowledge.js")
                .getContentAsString(StandardCharsets.UTF_8);
        assertTrue(css.contains(".admin-layout"));
        assertTrue(js.contains("createRoot"));
        assertTrue(css.contains("observability-page"));
    }

    @Test
    void servesSkillHubFrontendRouteForBrowserNavigation() throws Exception {
        mockMvc.perform(get("/admin/skills").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(ADMIN_TITLE)))
                .andExpect(content().string(containsString("id=\"root\"")))
                .andExpect(content().string(containsString("admin-knowledge.css")))
                .andExpect(content().string(containsString("admin-knowledge.js")));
    }

    @Test
    void servesRdTaskFrontendRoutesForBrowserNavigation() throws Exception {
        for (String route : new String[]{
                "/admin/rd-tasks",
                "/admin/rd-tasks/7477053417710555136"
        }) {
            mockMvc.perform(get(route).accept(MediaType.TEXT_HTML))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(ADMIN_TITLE)))
                    .andExpect(content().string(containsString("id=\"root\"")))
                    .andExpect(content().string(containsString("admin-knowledge.css")))
                    .andExpect(content().string(containsString("admin-knowledge.js")));
        }
    }

    @Test
    void servesEvaluationConsoleForDirectBrowserNavigation() throws Exception {
        mockMvc.perform(get("/admin/evaluations").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(ADMIN_TITLE)))
                .andExpect(content().string(containsString("id=\"root\"")))
                .andExpect(content().string(containsString("admin-knowledge.css")))
                .andExpect(content().string(containsString("admin-knowledge.js")));
    }
}
