package com.wish.rd.bootstrap.controller.admin.projectmemory;

import com.wish.rd.engine.admin.projectmemory.impl.FailClosedProjectMemoryMutationAuthorizer;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryAdminMutationService;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryAdminService;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryGovernanceAuditSink;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryMutationAction;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryMutationCapability;
import com.wish.rd.engine.admin.projectmemory.TrustedOperatorPrincipal;
import com.wish.rd.engine.admin.projectmemory.TrustedOperatorPrincipalProvider;
import com.wish.rd.engine.admin.projectmemory.impl.InMemoryProjectMemoryPurgeConfirmTokenStore;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryPurgeService;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.project.RdProjectService;
import com.wish.rd.rag.project.RdProjectStore;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryAdminQueryPort;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryGovernancePort;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryPurgePort;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryStore;
import com.wish.rd.rag.project.memory.model.ProjectMemory;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRetrievalAuditEntry;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Project memory admin API: host principal, pagination, safe errors, redacted DTOs. */
class ProjectMemoryAdminControllerTest {

    private static final String PROJECT = "101";
    private static final String DISABLED_PROJECT = "102";
    private static final String HASH = "a".repeat(64);
    private static final String SENSITIVE = "apiKey=super-secret raw Pi event at com.wish.internal";

    @Test
    void shouldWrapListAndDetailInDataEnvelopeWithPagination() throws Exception {
        Fixture fixture = Fixture.enabledGovernance();
        fixture.seedProject(PROJECT, true);
        fixture.seedActiveMemory();

        MockMvc mvc = fixture.mvc();
        mvc.perform(get("/admin/projects/{projectId}/memories", PROJECT).param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].memoryId").value("memory-1"))
                .andExpect(jsonPath("$.data.records[0].headStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.mutationsEnabled").value(true))
                .andExpect(jsonPath("$.data.projectReadOnly").value(false));

        mvc.perform(get("/admin/projects/{projectId}/memories/{memoryId}", PROJECT, "memory-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memoryId").value("memory-1"))
                .andExpect(jsonPath("$.data.revisions[0].contentHash").value(HASH))
                .andExpect(jsonPath("$.data.revisions[0].contentJson").doesNotExist())
                .andExpect(jsonPath("$.data.sources[0].redactedSummary").value("redacted summary"))
                .andExpect(jsonPath("$.data.sources[0].rawToolOutput").doesNotExist())
                .andExpect(jsonPath("$.data.retrievalAudits[0].querySummary").value("build command"));
    }

    @Test
    void shouldPaginateMemorySummaries() throws Exception {
        Fixture fixture = Fixture.enabledGovernance();
        fixture.seedProject(PROJECT, true);
        fixture.seedActiveMemory();
        fixture.seedMemory("memory-2", "fact-two");

        fixture.mvc()
                .perform(get("/admin/projects/{projectId}/memories", PROJECT).param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].memoryId").value("memory-1"))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.page").value(1));
    }

    @Test
    void shouldReturn404ForUnknownProjectOrMemory() throws Exception {
        Fixture fixture = Fixture.enabledGovernance();
        fixture.seedProject(PROJECT, true);
        fixture.seedActiveMemory();

        MockMvc mvc = fixture.mvc();
        mvc.perform(get("/admin/projects/{projectId}/memories", "999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());

        mvc.perform(get("/admin/projects/{projectId}/memories/{memoryId}", PROJECT, "missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void shouldConflictWhenRowVersionIsStale() throws Exception {
        Fixture fixture = Fixture.enabledGovernance();
        fixture.seedProject(PROJECT, true);
        fixture.seedActiveMemory();
        fixture.seedCandidateOnly();

        fixture.mvc()
                .perform(post("/admin/projects/{projectId}/memories/{memoryId}/confirm", PROJECT, "memory-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"revisionId":"revision-candidate","expectedMemoryRowVersion":99,
                                 "expectedRevisionRowVersion":1,"requestId":"req-conflict","requestedActor":"forged"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("row version")));
    }

    @Test
    void shouldRejectMutationsWhenPrincipalDeniedOrMissingCapability() throws Exception {
        Fixture fixture = Fixture.enabledGovernance();
        fixture.seedProject(PROJECT, true);
        fixture.seedActiveMemory();
        fixture.principalProvider.clear();

        MockMvc mvc = fixture.mvc();
        mvc.perform(post("/admin/projects/{projectId}/memories/{memoryId}/invalidate", PROJECT, "memory-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"revisionId":"revision-1","expectedMemoryRowVersion":2,
                                 "expectedRevisionRowVersion":1,"requestId":"req-deny"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").exists());

        fixture.principalProvider.setPrincipal(new TrustedOperatorPrincipal(
                "operator-2", Set.of(), Set.of(PROJECT)
        ));
        mvc.perform(post("/admin/projects/{projectId}/memories/{memoryId}/invalidate", PROJECT, "memory-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"revisionId":"revision-1","expectedMemoryRowVersion":2,
                                 "expectedRevisionRowVersion":1,"requestId":"req-cap"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("PROJECT_MEMORY_GOVERN")));
    }

    @Test
    void shouldAllowReadsButRejectMutationsWhenProjectDisabled() throws Exception {
        Fixture fixture = Fixture.enabledGovernance();
        fixture.seedProject(DISABLED_PROJECT, false);
        fixture.seedMemoryOnProject("memory-9", "disabled-key", DISABLED_PROJECT);

        MockMvc mvc = fixture.mvc();
        mvc.perform(get("/admin/projects/{projectId}/memories", DISABLED_PROJECT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectReadOnly").value(true))
                .andExpect(jsonPath("$.data.records[0].memoryId").value("memory-9"));

        mvc.perform(post("/admin/projects/{projectId}/memories/{memoryId}/soft-delete", DISABLED_PROJECT, "memory-9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedMemoryRowVersion\":1,\"requestId\":\"req-readonly\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("disabled")));
    }

    @Test
    void shouldRejectMutationsWhenGovernanceDisabled() throws Exception {
        Fixture fixture = Fixture.readOnly();
        fixture.seedProject(PROJECT, true);
        fixture.seedActiveMemory();

        fixture.mvc()
                .perform(post("/admin/projects/{projectId}/memories/{memoryId}/confirm", PROJECT, "memory-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"revisionId":"revision-candidate","expectedMemoryRowVersion":1,
                                 "expectedRevisionRowVersion":1,"requestId":"req-disabled"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(containsString("disabled")));
    }

    @Test
    void shouldValidateMutationRequestBodies() throws Exception {
        Fixture fixture = Fixture.enabledGovernance();
        fixture.seedProject(PROJECT, true);
        fixture.seedActiveMemory();

        MvcResult result = fixture.mvc()
                .perform(post("/admin/projects/{projectId}/memories/{memoryId}/confirm", PROJECT, "memory-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revisionId\":\"revision-candidate\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("expectedMemoryRowVersion")))
                .andReturn();
        assertNoInternalLeak(result.getResponse().getContentAsString());
    }

    @Test
    void shouldUseHostPrincipalNotBodyActor() throws Exception {
        Fixture fixture = Fixture.enabledGovernance();
        fixture.seedProject(PROJECT, true);
        fixture.seedCandidateOnly();
        fixture.principalProvider.setPrincipal(new TrustedOperatorPrincipal(
                "host-operator",
                Set.of(ProjectMemoryMutationCapability.PROJECT_MEMORY_GOVERN),
                Set.of(PROJECT)
        ));

        fixture.mvc()
                .perform(post("/admin/projects/{projectId}/memories/{memoryId}/confirm", PROJECT, "memory-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"revisionId":"revision-candidate","expectedMemoryRowVersion":1,
                                 "expectedRevisionRowVersion":1,"requestId":"req-host","requestedActor":"forged"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operatorId").value("host-operator"))
                .andExpect(jsonPath("$.data.requestedActor").doesNotExist());
    }

    @Test
    void shouldTranslateErrorsSafelyWithoutLeakingInternals() throws Exception {
        Fixture fixture = Fixture.enabledGovernance();
        fixture.seedProject(PROJECT, true);

        MvcResult result = fixture.mvc()
                .perform(get("/admin/projects/{projectId}/memories/{memoryId}", PROJECT, "missing"))
                .andExpect(status().isNotFound())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertNoInternalLeak(body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains(SENSITIVE), body);
    }

    @Test
    void shouldNotExposeSensitiveFieldsInDetailDto() throws Exception {
        Fixture fixture = Fixture.enabledGovernance();
        fixture.seedProject(PROJECT, true);
        fixture.seedActiveMemoryWithSensitiveSource();

        MvcResult result = fixture.mvc()
                .perform(get("/admin/projects/{projectId}/memories/{memoryId}", PROJECT, "memory-1"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("apiKey"), body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("raw Pi event"), body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("contentJson"), body);
    }

    private static void assertNoInternalLeak(String body) {
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("at com.wish"), body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("Exception"), body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("stackTrace"), body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("trace"), body);
        org.hamcrest.MatcherAssert.assertThat(body, not(containsString("java.lang.")));
    }

    private static final class Fixture {
        private final InMemoryProjectMemoryStore store = new InMemoryProjectMemoryStore();
        private final InMemoryProjectMemoryAdminQueryPort queryPort = new InMemoryProjectMemoryAdminQueryPort(store);
        private final InMemoryProjectMemoryGovernancePort governancePort = new InMemoryProjectMemoryGovernancePort(store);
        private final RecordingAuditSink auditSink = new RecordingAuditSink();
        private final MutablePrincipalProvider principalProvider = new MutablePrincipalProvider();
        private final FakeRdProjectStore projectStore = new FakeRdProjectStore();
        private final RdProjectService projectService = new RdProjectService(
                SnowflakeIdGenerator.defaultGenerator(), projectStore
        );
        private final ProjectMemoryAdminService adminService = new ProjectMemoryAdminService(queryPort, store);
        private final ProjectMemoryAdminMutationService mutationService;
        private final ProjectMemoryAdminController controller;

        private Fixture(boolean mutationsEnabled) {
            FailClosedProjectMemoryMutationAuthorizer authorizer =
                    new FailClosedProjectMemoryMutationAuthorizer(auditSink);
            mutationService = new ProjectMemoryAdminMutationService(
                    mutationsEnabled ? Optional.of(principalProvider) : Optional.empty(),
                    authorizer,
                    governancePort
            );
            InMemoryProjectMemoryPurgePort purgePort = new InMemoryProjectMemoryPurgePort(store, queryPort);
            ProjectMemoryPurgeService purgeService = new ProjectMemoryPurgeService(
                    mutationsEnabled ? Optional.of(principalProvider) : Optional.empty(),
                    authorizer,
                    purgePort,
                    new InMemoryProjectMemoryPurgeConfirmTokenStore()
            );
            controller = new ProjectMemoryAdminController(
                    projectService, adminService, mutationService, purgeService
            );
        }

        static Fixture enabledGovernance() {
            Fixture fixture = new Fixture(true);
            fixture.principalProvider.setPrincipal(new TrustedOperatorPrincipal(
                    "operator-1",
                    Set.of(ProjectMemoryMutationCapability.PROJECT_MEMORY_GOVERN),
                    Set.of(PROJECT, DISABLED_PROJECT)
            ));
            return fixture;
        }

        static Fixture readOnly() {
            return new Fixture(false);
        }

        MockMvc mvc() {
            return MockMvcBuilders.standaloneSetup(controller).build();
        }

        void seedProject(String projectId, boolean enabled) {
            long now = 1_800_000_000_000L;
            projectStore.save(new RdProject(
                    projectId, "proj-" + projectId, "Project " + projectId, "",
                    "https://example.com/repo.git", "owner", "repo", "main",
                    enabled, false, now, now, ""
            ));
        }

        void seedActiveMemory() {
            seedMemory("memory-1", "build-command");
            ProjectMemoryRevision revision = revision("revision-1", "memory-1", 1L, ProjectMemoryRevisionStatus.ACTIVE);
            ProjectMemorySource source = new ProjectMemorySource(
                    "source-1", revision.revisionId(), PROJECT, "task-9", "stage-1", "artifact-1",
                    "rd-artifact://task-9", HASH, "abc123", "extractor-1", "schema-1", "redacted summary"
            );
            store.addRevision(revision);
            store.advanceHead("memory-1", revision.revisionId(), 1L);
            store.addSource(source);
            queryPort.recordRetrievalAudit(new ProjectMemoryRetrievalAuditEntry(
                    "memory-1", revision.revisionId(), revision.version(), "build command", 3, 1_700_000_000_000L
            ));
        }

        void seedActiveMemoryWithSensitiveSource() {
            ProjectMemory memory = store.create(new ProjectMemory(
                    "memory-1", PROJECT, "CODING_AGENT", ProjectMemoryType.PROCEDURAL, "build-command", 1L
            ));
            ProjectMemoryRevision revision = new ProjectMemoryRevision(
                    "revision-1", memory.memoryId(), 1L, ProjectMemoryRevisionStatus.ACTIVE,
                    "title", "summary", "{\"toolOutput\":\"" + SENSITIVE + "\"}",
                    HASH, "schema-1", "", 1L, 1L
            );
            store.addRevision(revision);
            store.advanceHead(memory.memoryId(), revision.revisionId(), 1L);
            store.addSource(new ProjectMemorySource(
                    "source-1", revision.revisionId(), PROJECT, "task-9", "stage-1", "artifact-1",
                    "rd-artifact://task-9", HASH, "abc123", "extractor-1", "schema-1", "redacted summary"
            ));
        }

        void seedCandidateOnly() {
            seedMemory("memory-2", "candidate-fact");
            store.addRevision(revision("revision-candidate", "memory-2", 1L, ProjectMemoryRevisionStatus.CANDIDATE));
        }

        void seedMemory(String memoryId, String logicalKey) {
            store.create(new ProjectMemory(
                    memoryId, PROJECT, "CODING_AGENT", ProjectMemoryType.PROCEDURAL, logicalKey, 1L
            ));
        }

        void seedMemoryOnProject(String memoryId, String logicalKey, String projectId) {
            store.create(new ProjectMemory(
                    memoryId, projectId, "CODING_AGENT", ProjectMemoryType.PROCEDURAL, logicalKey, 1L
            ));
            store.addRevision(revision("revision-" + memoryId, memoryId, 1L, ProjectMemoryRevisionStatus.ACTIVE));
            store.advanceHead(memoryId, "revision-" + memoryId, 1L);
        }
    }

    private static ProjectMemoryRevision revision(
            String revisionId,
            String memoryId,
            long version,
            ProjectMemoryRevisionStatus status
    ) {
        return new ProjectMemoryRevision(
                revisionId, memoryId, version, status, "title", "summary", "{\"command\":\"npm test\"}",
                HASH, "schema-1", "", 1L, 1L
        );
    }

    private static final class MutablePrincipalProvider implements TrustedOperatorPrincipalProvider {
        private TrustedOperatorPrincipal principal;

        void setPrincipal(TrustedOperatorPrincipal principal) {
            this.principal = principal;
        }

        void clear() {
            this.principal = null;
        }

        @Override
        public Optional<TrustedOperatorPrincipal> current() {
            return Optional.ofNullable(principal);
        }
    }

    private static final class RecordingAuditSink implements ProjectMemoryGovernanceAuditSink {
        private final CopyOnWriteArrayList<String> allowed = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> denied = new CopyOnWriteArrayList<>();

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
            denied.add(principal.operatorId() + "|" + projectId + "|" + action);
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
