package com.wish.rd.bootstrap.controller.admin.knowledge;

import com.wish.rd.bootstrap.controller.admin.AdminFrontendController;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexReconcileEngine;
import com.wish.rd.rag.knowledge.projection.KnowledgeProjectionAdminEngine;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeReconcileFindingStore;
import com.wish.rd.rag.knowledge.projection.model.ProjectionWorkerSettings;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentStore;
import com.wish.rd.bootstrap.openviking.impl.DisabledExternalKnowledgeIndexPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPA 页面路由与嵌套 JSON API 必须分道：HTML 导航回 index.html，
 * knowledge-base 下的 openviking API 永远到达后端。
 */
class KnowledgeProjectionAdminSpaRouteTest {

    @Test
    void htmlNavigationServesTheSpaWhileNestedApisStayOnTheBackend() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AdminFrontendController(),
                new KnowledgeProjectionAdminController(engine())
        ).build();

        mvc.perform(get("/admin/knowledge/123/openviking").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("id=\"root\"")))
                .andExpect(content().string(containsString("admin-knowledge.js")));

        mvc.perform(get("/admin/knowledge-base/123/openviking/overview")
                        .accept(MediaType.TEXT_HTML, MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"root\""))))
                .andExpect(jsonPath("$.data.ready").exists())
                .andExpect(jsonPath("$.data.bindingCounts").exists());
    }

    private static KnowledgeProjectionAdminEngine engine() {
        DisabledExternalKnowledgeIndexPort port = new DisabledExternalKnowledgeIndexPort();
        InMemoryKnowledgeExternalIndexBindingStore bindings = new InMemoryKnowledgeExternalIndexBindingStore();
        InMemoryKnowledgeExternalIndexOutboxStore outbox = new InMemoryKnowledgeExternalIndexOutboxStore();
        InMemoryKnowledgeReconcileFindingStore findings = new InMemoryKnowledgeReconcileFindingStore();
        InMemoryKnowledgeDocumentStore documents = new InMemoryKnowledgeDocumentStore();
        KnowledgeExternalIndexReconcileEngine reconciler = new KnowledgeExternalIndexReconcileEngine(
                port, bindings, outbox, findings, ProjectionWorkerSettings.defaults(), 0L, () -> "1");
        return new KnowledgeProjectionAdminEngine(
                port, bindings, outbox, findings, documents, reconciler,
                ProjectionWorkerSettings.defaults(), () -> "1");
    }
}
