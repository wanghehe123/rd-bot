package com.wish.rd.engine.admin.dashboard;

import com.wish.rd.engine.admin.dashboard.model.RdDashboardOverview;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.knowledge.model.KnowledgeBase;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentStore;
import com.wish.rd.rag.project.RdProjectService;
import com.wish.rd.rag.project.RdProjectStore;
import com.wish.rd.rag.project.model.RdProject;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdDashboardQueryServiceTest {

    @Test
    void shouldAggregateOnlyTasksBelongingToSelectedProject() {
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(taskStore, new InMemoryRdTaskStatusEventStore(), generator());
        InMemoryKnowledgeBaseStore knowledgeBases = new InMemoryKnowledgeBaseStore();
        knowledgeBases.save(new KnowledgeBase("kb-1", "项目一知识库", "", true, 1L));
        InMemoryKnowledgeDocumentStore documents = new InMemoryKnowledgeDocumentStore();
        RdProjectService projects = projects("kb-1");

        taskStore.saveRequirementTask(requirement("req-executing", "project-1", RdTaskStatus.EXECUTING, 100L));
        taskStore.saveRequirementTask(requirement("req-completed", "project-1", RdTaskStatus.COMPLETED, 101L));
        taskStore.saveBugFixTask(bug("bug-waiting", "project-1", RdTaskStatus.WAITING_APPROVAL, 102L));
        taskStore.saveBugFixTask(bug("bug-human", "project-1", RdTaskStatus.FAILED_NEEDS_HUMAN, 103L));
        taskStore.saveBugFixTask(bug("bug-rejected", "project-1", RdTaskStatus.REJECTED, 104L));
        taskStore.saveBugFixTask(bug("bug-other-project", "project-2", RdTaskStatus.MERGED, 105L));

        RdDashboardQueryService service = new RdDashboardQueryService(
                registry,
                projects,
                knowledgeBases,
                documents,
                DashboardRuntimeSnapshotPort.unavailable()
        );

        RdDashboardOverview result = service.overview("project-1", 10);

        assertEquals("项目一", result.projectName());
        assertEquals(2L, result.requirementCount());
        assertEquals(3L, result.bugFixCount());
        assertEquals(1L, result.inProgressCount());
        assertEquals(2L, result.waitingHumanCount());
        assertEquals(1L, result.completedCount());
        assertEquals(2L, result.blockedCount());
        assertEquals(new BigDecimal("0.3333"), result.successRate().value());
        assertTrue(result.successRate().available());
        assertEquals(1L, result.statusCounts().get("EXECUTING"));
        assertEquals("bug-rejected", result.recentDeliveries().getFirst().taskId());
    }

    @Test
    void shouldNotRepresentMissingTerminalSamplesAsZeroSuccessRate() {
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(taskStore, new InMemoryRdTaskStatusEventStore(), generator());
        InMemoryKnowledgeBaseStore knowledgeBases = new InMemoryKnowledgeBaseStore();
        InMemoryKnowledgeDocumentStore documents = new InMemoryKnowledgeDocumentStore();
        RdProjectService projects = projects("");
        taskStore.saveRequirementTask(requirement("req-running", "project-1", RdTaskStatus.EXECUTING, 200L));

        RdDashboardOverview result = new RdDashboardQueryService(
                registry,
                projects,
                knowledgeBases,
                documents,
                DashboardRuntimeSnapshotPort.unavailable()
        ).overview("project-1", 10);

        assertFalse(result.successRate().available());
        assertEquals(null, result.successRate().value());
    }

    @Test
    void shouldKeepAllProjectsDashboardAvailableWithoutProjectManagement() {
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(taskStore, new InMemoryRdTaskStatusEventStore(), generator());
        taskStore.saveBugFixTask(bug("bug-global", "project-1", RdTaskStatus.EXECUTING, 300L));

        RdDashboardOverview result = new RdDashboardQueryService(
                registry,
                (RdProjectService) null,
                new InMemoryKnowledgeBaseStore(),
                new InMemoryKnowledgeDocumentStore(),
                DashboardRuntimeSnapshotPort.unavailable()
        ).overview("", 10);

        assertEquals("", result.projectId());
        assertEquals("全部项目", result.projectName());
        assertEquals(1L, result.bugFixCount());
    }

    private static RdRequirementTask requirement(String id, String projectId, RdTaskStatus status, long updatedAt) {
        return new RdRequirementTask(
                id, "REQUIREMENT", "ADMIN", "", "", "P1", status, id,
                projectId, projectId + "-key", "项目一", "https://example.test/repo.git", "example", "repo", "main", "",
                "完成交付", "[]", "", "{}", "", "", 1L, updatedAt, false
        );
    }

    private static RdBugFixTask bug(String id, String projectId, RdTaskStatus status, long updatedAt) {
        return new RdBugFixTask(
                id, "BUG_FIX", id, id, "P1", status, "", id, "", "{}", "", "",
                projectId, projectId + "-key", "项目一", "https://example.test/repo.git", "example", "repo", "main",
                1L, updatedAt, false
        );
    }

    private static RdProjectService projects(String knowledgeBaseId) {
        InMemoryProjectStore store = new InMemoryProjectStore();
        store.save(project("project-1", "项目一", knowledgeBaseId));
        store.save(project("project-2", "项目二", ""));
        return new RdProjectService(generator(), store);
    }

    private static RdProject project(String id, String name, String knowledgeBaseId) {
        return new RdProject(id, id + "-key", name, "", "https://example.test/repo.git", "example", "repo", "main", true, false, 1L, 1L, knowledgeBaseId);
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
