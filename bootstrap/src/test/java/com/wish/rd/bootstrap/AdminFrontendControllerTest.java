package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminFrontendControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void servesKnowledgeAdminFrontendRoutesFromSingleSpringBootService() throws Exception {
        mockMvc.perform(get("/admin/knowledge"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ragent 管理后台")))
                .andExpect(content().string(containsString("id=\"root\"")))
                .andExpect(content().string(containsString("admin-knowledge.js")));

        mockMvc.perform(get("/admin/knowledge/kb-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ragent 管理后台")));

        mockMvc.perform(get("/admin/knowledge/kb-1/docs/doc-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ragent 管理后台")));
    }

    @Test
    void servesMigratedIntentAndUserAdminFrontendRoutes() throws Exception {
        mockMvc.perform(get("/admin/intent-tree"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ragent 管理后台")))
                .andExpect(content().string(containsString("id=\"root\"")))
                .andExpect(content().string(containsString("admin-knowledge.js")));

        mockMvc.perform(get("/admin/intent-list"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ragent 管理后台")));

        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ragent 管理后台")));
    }

    @Test
    void servesRagentStyleReactAdminRoutesAndAssets() throws Exception {
        for (String route : new String[]{
                "/admin/dashboard",
                "/admin/ingestion",
                "/admin/mappings",
                "/admin/traces",
                "/admin/traces/trace-ticket-prompt-flow",
                "/admin/sample-questions",
                "/admin/settings"
        }) {
            mockMvc.perform(get(route))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Ragent 管理后台")))
                    .andExpect(content().string(containsString("id=\"root\"")))
                    .andExpect(content().string(containsString("admin-knowledge.css")))
                    .andExpect(content().string(containsString("admin-knowledge.js")));
        }

        mockMvc.perform(get("/admin/admin-knowledge.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(".admin-layout")));

        mockMvc.perform(get("/admin/admin-knowledge.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("createRoot")));
    }
}
