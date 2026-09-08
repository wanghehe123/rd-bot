package com.wish.rd.bootstrap.controller.admin.projectmemory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.admin.projectmemory.impl.FailClosedProjectMemoryMutationAuthorizer;
import com.wish.rd.engine.admin.projectmemory.impl.InMemoryProjectMemoryPurgeConfirmTokenStore;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryAdminMutationService;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryAdminService;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryGovernanceAuditSink;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryMutationAction;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryMutationCapability;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryPurgeService;
import com.wish.rd.engine.admin.projectmemory.TrustedOperatorPrincipal;
import com.wish.rd.engine.admin.projectmemory.TrustedOperatorPrincipalProvider;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.project.RdProjectService;
import com.wish.rd.rag.project.RdProjectStore;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryAdminQueryPort;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryGovernancePort;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryPurgePort;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryStore;
import com.wish.rd.rag.project.memory.model.ProjectMemory;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevision;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevisionStatus;
import com.wish.rd.rag.project.memory.model.ProjectMemorySource;
import com.wish.rd.rag.project.memory.model.ProjectMemoryType;
import com.wish.rd.rag.project.model.RdProject;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProjectMemoryPurgeControllerTest {

    private static final String PROJECT = "101";
    private static final String OTHER_PROJECT = "202";
    private static final String HASH = "a".repeat(64);
    private static final String REASON = "retire stale canary project memory";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void shouldPreviewAndExecutePurgeThroughHttpProtocol() throws Exception {
        Fixture fixture = Fixture.enabledPurge();
        fixture.seedProject(PROJECT, true);
        fixture.seedActiveMemory();
        MockMvc mvc = fixture.mvc();

        MvcResult previewResult = mvc.perform(post("/admin/projects/{projectId}/memories/purge/preview", PROJECT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"%s","requestId":"req-preview","requestedActor":"forged"}
                                """.formatted(REASON)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectId").value(PROJECT))
                .andExpect(jsonPath("$.data.operatorId").value("purge-operator"))
                .andExpect(jsonPath("$.data.expectedCounts.totalRowCount").value(3))
                .andExpect(jsonPath("$.data.confirmToken").isNotEmpty())
                .andReturn();

        String confirmToken = JSON.readTree(previewResult.getResponse().getContentAsString())
                .path("data")
                .path("confirmToken")
                .asText();

        mvc.perform(post("/admin/projects/{projectId}/memories/purge/execute", PROJECT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"%s","confirmToken":"%s","requestId":"req-execute","requestedActor":"forged"}
                                """.formatted(REASON, confirmToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actualCounts.totalRowCount").value(3))
                .andExpect(jsonPath("$.data.expectedCounts.totalRowCount").value(3));

        assertEquals(0, fixture.store.listByProject(PROJECT).size());
        assertEquals(2, fixture.auditSink.allowed.stream().filter(entry -> entry.contains("PURGE_")).count());
    }

    @Test
    void shouldRejectCrossProjectPurgePreviewWith403() throws Exception {
        Fixture fixture = Fixture.enabledPurge();
        fixture.seedProject(PROJECT, true);
        fixture.seedActiveMemory();
        fixture.principalProvider.setPrincipal(new TrustedOperatorPrincipal(
                "purge-operator",
                Set.of(ProjectMemoryMutationCapability.PROJECT_MEMORY_PURGE),
                Set.of(OTHER_PROJECT)
        ));

        fixture.mvc()
                .perform(post("/admin/projects/{projectId}/memories/purge/preview", PROJECT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"%s","requestId":"req-cross","requestedActor":"ignored"}
                                """.formatted(REASON)))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectExecuteWhenConfirmTokenDoesNotSubstitutePermission() throws Exception {
        Fixture fixture = Fixture.enabledPurge();
        fixture.seedProject(PROJECT, true);
        fixture.seedActiveMemory();

        JsonNode preview = JSON.readTree(fixture.mvc()
                .perform(post("/admin/projects/{projectId}/memories/purge/preview", PROJECT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"%s","requestId":"req-preview","requestedActor":"ignored"}
                                """.formatted(REASON)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        String confirmToken = preview.path("data").path("confirmToken").asText();

        fixture.principalProvider.setPrincipal(new TrustedOperatorPrincipal(
                "purge-operator",
                Set.of(ProjectMemoryMutationCapability.PROJECT_MEMORY_GOVERN),
                Set.of(PROJECT)
        ));

        fixture.mvc()
                .perform(post("/admin/projects/{projectId}/memories/purge/execute", PROJECT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"%s","confirmToken":"%s","requestId":"req-no-cap","requestedActor":"ignored"}
                                """.formatted(REASON, confirmToken)))
                .andExpect(status().isForbidden());

        assertEquals(1, fixture.store.listByProject(PROJECT).size());
    }

    @Test
    void shouldReturnGoneForExpiredConfirmToken() throws Exception {
        Fixture fixture = Fixture.enabledPurge();
        fixture.seedProject(PROJECT, true);
        fixture.seedActiveMemory();

        JsonNode preview = JSON.readTree(fixture.mvc()
                .perform(post("/admin/projects/{projectId}/memories/purge/preview", PROJECT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"%s","requestId":"req-preview","requestedActor":"ignored"}
                                """.formatted(REASON)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        String confirmToken = preview.path("data").path("confirmToken").asText();
        fixture.now.addAndGet(ProjectMemoryPurgeService.DEFAULT_CONFIRM_TOKEN_TTL_MILLIS + 1L);

        fixture.mvc()
                .perform(post("/admin/projects/{projectId}/memories/purge/execute", PROJECT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"%s","confirmToken":"%s","requestId":"req-expired","requestedActor":"ignored"}
                                """.formatted(REASON, confirmToken)))
                .andExpect(status().isGone());
    }

    private static final class Fixture {
        private final InMemoryProjectMemoryStore store = new InMemoryProjectMemoryStore();
        private final InMemoryProjectMemoryAdminQueryPort queryPort = new InMemoryProjectMemoryAdminQueryPort(store);
        private final InMemoryProjectMemoryGovernancePort governancePort = new InMemoryProjectMemoryGovernancePort(store);
        private final InMemoryProjectMemoryPurgePort purgePort = new InMemoryProjectMemoryPurgePort(store, queryPort);
        private final RecordingAuditSink auditSink = new RecordingAuditSink();
        private final MutablePrincipalProvider principalProvider = new MutablePrincipalProvider();
        private final AtomicLong now = new AtomicLong(1_700_000_000_000L);
        private final FakeRdProjectStore projectStore = new FakeRdProjectStore();
        private final RdProjectService projectService = new RdProjectService(
                SnowflakeIdGenerator.defaultGenerator(), projectStore
        );
        private final ProjectMemoryAdminController controller;

        private Fixture(boolean purgeEnabled) {
            FailClosedProjectMemoryMutationAuthorizer authorizer =
                    new FailClosedProjectMemoryMutationAuthorizer(auditSink);
            ProjectMemoryAdminMutationService mutationService = new ProjectMemoryAdminMutationService(
                    purgeEnabled ? Optional.of(principalProvider) : Optional.empty(),
                    authorizer,
                    governancePort
            );
            ProjectMemoryPurgeService purgeService = new ProjectMemoryPurgeService(
                    purgeEnabled ? Optional.of(principalProvider) : Optional.empty(),
                    authorizer,
                    purgePort,
                    new InMemoryProjectMemoryPurgeConfirmTokenStore(),
                    now::get
            );
            ProjectMemoryAdminService adminService = new ProjectMemoryAdminService(queryPort, store);
            controller = new ProjectMemoryAdminController(
                    projectService, adminService, mutationService, purgeService
            );
            if (purgeEnabled) {
                principalProvider.setPrincipal(new TrustedOperatorPrincipal(
                        "purge-operator",
                        Set.of(ProjectMemoryMutationCapability.PROJECT_MEMORY_PURGE),
                        Set.of(PROJECT)
                ));
            }
        }

        static Fixture enabledPurge() {
            return new Fixture(true);
        }

        MockMvc mvc() {
            return MockMvcBuilders.standaloneSetup(controller).build();
        }

        void seedProject(String projectId, boolean enabled) {
            long timestamp = now.get();
            projectStore.save(new RdProject(
                    projectId, "proj-" + projectId, "Project " + projectId, "",
                    "https://example.com/repo.git", "owner", "repo", "main",
                    enabled, false, timestamp, timestamp, ""
            ));
        }

        void seedActiveMemory() {
            ProjectMemory memory = store.create(new ProjectMemory(
                    "memory-1", PROJECT, "CODING_AGENT", ProjectMemoryType.PROCEDURAL, "build-command", 1L
            ));
            ProjectMemoryRevision revision = new ProjectMemoryRevision(
                    "revision-1", memory.memoryId(), 1L, ProjectMemoryRevisionStatus.ACTIVE,
                    "title", "summary", "{}", HASH, "schema-1", "", 1L, 1L
            );
            ProjectMemorySource source = new ProjectMemorySource(
                    "source-1", revision.revisionId(), PROJECT, "task-9", "stage-1", "artifact-1",
                    "rd-artifact://task-9", HASH, "abc123", "extractor-1", "schema-1", "redacted summary"
            );
            store.addRevision(revision);
            store.advanceHead(memory.memoryId(), revision.revisionId(), 1L);
            store.addSource(source);
        }
    }

    private static final class MutablePrincipalProvider implements TrustedOperatorPrincipalProvider {
        private TrustedOperatorPrincipal principal;

        void setPrincipal(TrustedOperatorPrincipal principal) {
            this.principal = principal;
        }

        @Override
        public Optional<TrustedOperatorPrincipal> current() {
            return Optional.ofNullable(principal);
        }
    }

    private static final class RecordingAuditSink implements ProjectMemoryGovernanceAuditSink {
        private final CopyOnWriteArrayList<String> allowed = new CopyOnWriteArrayList<>();

        @Override
        public void recordAllowed(
                TrustedOperatorPrincipal principal,
                String projectId,
                ProjectMemoryMutationAction action,
                String requestId,
                String reason
        ) {
            allowed.add(principal.operatorId() + "|" + projectId + "|" + action);
        }

        @Override
        public void recordDenied(
                TrustedOperatorPrincipal principal,
                String projectId,
                ProjectMemoryMutationAction action,
                String requestId,
                String reason
        ) {
            allowed.add("deny|" + projectId + "|" + action);
        }
    }

    private static final class FakeRdProjectStore implements RdProjectStore {
        private final Map<String, RdProject> projects = new LinkedHashMap<>();

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
            return projects.values().stream()
                    .filter(project -> project.projectKey().equals(projectKey))
                    .findFirst();
        }

        @Override
        public List<RdProject> list() {
            return List.copyOf(projects.values());
        }
    }
}
