package com.wish.rd.bootstrap.controller.admin.dashboard;

import com.wish.rd.engine.admin.dashboard.DashboardRuntimeSnapshotPort;
import com.wish.rd.engine.admin.dashboard.RdDashboardQueryService;
import com.wish.rd.engine.admin.dashboard.model.RdDashboardOverview;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentStore;
import com.wish.rd.rag.project.RdProjectService;
import com.wish.rd.rag.project.RdProjectStore;
import com.wish.rd.rag.project.model.RdProject;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RdDashboardControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        taskStore.saveBugFixTask(new RdBugFixTask(
                "task-1", "BUG_FIX", "ticket-1", "支付回调失败", "P1", RdTaskStatus.EXECUTING,
                "", "支付回调失败", "", "{}", "", "", "project-1", "p1", "项目一",
                "https://example.test/p1.git", "example", "p1", "main", 1L, 2L, false
        ));
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), generator());
        InMemoryProjectStore projectStore = new InMemoryProjectStore();
        projectStore.save(new RdProject(
                "project-1", "p1", "项目一", "", "https://example.test/p1.git", "example", "p1", "main",
                true, false, 1L, 1L, ""
        ));
        DashboardRuntimeSnapshotPort runtimePort = (projectId, taskIds) -> new DashboardRuntimeSnapshotPort.Snapshot(
                new RdDashboardOverview.RuntimeSnapshot(true, 0L, 0L, BigDecimal.ZERO, true),
                Map.of()
        );
        RdDashboardQueryService service = new RdDashboardQueryService(
                registry,
                new RdProjectService(generator(), projectStore),
                new InMemoryKnowledgeBaseStore(),
                new InMemoryKnowledgeDocumentStore(),
                runtimePort
        );
        mockMvc = MockMvcBuilders.standaloneSetup(new RdDashboardController(service)).build();
    }

    @Test
    void shouldReturnProjectDeliveryOverview() throws Exception {
        mockMvc.perform(get("/admin/dashboard/overview").param("projectId", "project-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId", is("project-1")))
                .andExpect(jsonPath("$.bugFixCount", is(1)))
                .andExpect(jsonPath("$.statusCounts.EXECUTING", is(1)))
                .andExpect(jsonPath("$.runtime.estimatedSpendCny").isNumber());
    }

    @Test
    void shouldRejectUnknownProjectAndNonPositiveLimit() throws Exception {
        mockMvc.perform(get("/admin/dashboard/overview").param("projectId", "missing"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/admin/dashboard/overview").param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    private static SnowflakeIdGenerator generator() {
        AtomicLong now = new AtomicLong(1_790_000_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }

    private static final class InMemoryProjectStore implements RdProjectStore {
        private final LinkedHashMap<String, RdProject> projects = new LinkedHashMap<>();

        @Override
        public RdProject save(RdProject project) {
            projects.put(project.projectId(), project);
            return project;
        }

        @Override
        public Optional<RdProject> findById(String projectId) {
            return Optional.ofNullable(projects.get(projectId));
        }

        @Override
        public Optional<RdProject> findByKey(String projectKey) {
            return projects.values().stream().filter(project -> project.projectKey().equals(projectKey)).findFirst();
        }

        @Override
        public List<RdProject> list() {
            return List.copyOf(projects.values());
        }
    }
}
