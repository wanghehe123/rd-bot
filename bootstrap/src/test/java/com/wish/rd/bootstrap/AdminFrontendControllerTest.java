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
                .andExpect(content().string(containsString("admin-knowledge.js")));

        mockMvc.perform(get("/admin/intent-list"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ragent 管理后台")));

        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ragent 管理后台")));
    }
}
