package com.wish.rd.engine.admin.projectmemory;

import com.wish.rd.engine.admin.projectmemory.impl.FailClosedProjectMemoryMutationAuthorizer;

import com.wish.rd.engine.admin.projectmemory.model.ProjectMemoryAdminDetailView;
import com.wish.rd.engine.admin.projectmemory.model.ProjectMemoryAdminMutationResult;
import com.wish.rd.engine.admin.projectmemory.model.ProjectMemoryAdminSummaryView;
import com.wish.rd.rag.project.memory.ProjectMemoryGovernanceConflictException;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryAdminQueryPort;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryGovernancePort;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryStore;
import com.wish.rd.rag.project.memory.model.ProjectMemory;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRetrievalAuditEntry;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevision;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevisionStatus;
import com.wish.rd.rag.project.memory.model.ProjectMemorySource;
import com.wish.rd.rag.project.memory.model.ProjectMemoryType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMemoryAdminServiceTest {

    private static final String PROJECT = "101";
    private static final String OTHER_PROJECT = "202";
    private static final String HASH = "a".repeat(64);

    @Test
    void shouldExposeHeadRevisionSourceStatusRoleAndRetrievalAuditByProject() {
        Fixture fixture = Fixture.enabledGovernance();

        ProjectMemoryAdminDetailView detail = fixture.adminService.getDetail(PROJECT, "memory-1");

        assertEquals("memory-1", detail.memoryId());
        assertEquals("CODING_AGENT", detail.scopeRole());
        assertEquals("revision-1", detail.headRevisionId());
        assertEquals(ProjectMemoryRevisionStatus.ACTIVE, detail.revisions().getFirst().status());
        assertTrue(detail.revisions().getFirst().head());
        assertEquals("task-9", detail.sources().getFirst().taskId());
        assertTrue(detail.sources().getFirst().originReferencesAvailable());
        assertEquals(1, detail.retrievalAudits().size());
        assertEquals("build command", detail.retrievalAudits().getFirst().querySummary());

        List<ProjectMemoryAdminSummaryView> summaries = fixture.adminService.listByProject(PROJECT);
        assertEquals(1, summaries.size());
        assertEquals(ProjectMemoryRevisionStatus.ACTIVE, summaries.getFirst().headStatus());
    }

    @Test
    void shouldConfirmCandidateRevisionWhenAuthorizedAndRowVersionsMatch() {
        Fixture fixture = Fixture.enabledGovernance();
        fixture.seedCandidateOnly();

        ProjectMemoryAdminMutationResult result = fixture.mutationService.confirm(
                new ProjectMemoryAdminMutationService.ProjectMemoryConfirmRequest(
                        PROJECT, "memory-2", "revision-candidate", 1L, 1L, "req-confirm", "body-actor"
                )
        );

        assertEquals("operator-1", result.operatorId());
        assertEquals(ProjectMemoryRevisionStatus.ACTIVE, result.revisionStatus());
        assertEquals("req-confirm", result.requestId());
        ProjectMemoryAdminDetailView detail = fixture.adminService.getDetail(PROJECT, "memory-2");
        assertEquals("revision-candidate", detail.headRevisionId());
        assertEquals(1, fixture.auditSink.allowed.size());
        assertTrue(fixture.auditSink.allowed.getFirst().contains("CONFIRM"));
    }

    @Test
    void shouldRejectConfirmWhenMemoryRowVersionConflicts() {
        Fixture fixture = Fixture.enabledGovernance();
        fixture.seedCandidateOnly();

        assertThrows(
                ProjectMemoryGovernanceConflictException.class,
                () -> fixture.mutationService.confirm(
                        new ProjectMemoryAdminMutationService.ProjectMemoryConfirmRequest(
                                PROJECT, "memory-2", "revision-candidate", 99L, 1L, "req-conflict", "ignored"
                        )
                )
        );
    }

    @Test
    void shouldCorrectActiveRevisionWithSupersedingRevision() {
        Fixture fixture = Fixture.enabledGovernance();

        ProjectMemoryAdminMutationResult result = fixture.mutationService.correct(
                new ProjectMemoryAdminMutationService.ProjectMemoryCorrectRequest(
                        PROJECT,
                        "memory-1",
                        "revision-1",
                        2L,
                        1L,
                        "corrected title",
                        "corrected summary",
                        "{\"command\":\"npm test\"}",
                        "b".repeat(64),
                        "revision-corrected",
                        "req-correct",
                        "body-actor"
                )
        );

        assertEquals("revision-corrected", result.revisionId());
        assertEquals(ProjectMemoryRevisionStatus.ACTIVE, result.revisionStatus());
        ProjectMemoryAdminDetailView detail = fixture.adminService.getDetail(PROJECT, "memory-1");
        assertEquals(2, detail.revisions().size());
        assertEquals(ProjectMemoryRevisionStatus.SUPERSEDED, detail.revisions().getFirst().status());
        assertEquals("revision-corrected", detail.headRevisionId());
    }

    @Test
    void shouldInvalidateRevisionWithoutDeletingMemoryAggregate() {
        Fixture fixture = Fixture.enabledGovernance();

        ProjectMemoryAdminMutationResult result = fixture.mutationService.invalidate(
                new ProjectMemoryAdminMutationService.ProjectMemoryInvalidateRequest(
                        PROJECT, "memory-1", "revision-1", 2L, 1L, "req-invalidate", "ignored"
                )
        );

        assertEquals(ProjectMemoryRevisionStatus.EXPIRED, result.revisionStatus());
        assertFalse(result.memoryDeleted());
        assertEquals(1, fixture.store.listByProject(PROJECT).size());
    }

    @Test
    void shouldSoftDeleteMemoryWithExpectedRowVersion() {
        Fixture fixture = Fixture.enabledGovernance();

        ProjectMemoryAdminMutationResult result = fixture.mutationService.softDelete(
                new ProjectMemoryAdminMutationService.ProjectMemorySoftDeleteRequest(
                        PROJECT, "memory-1", 2L, "req-delete", "ignored"
                )
        );

        assertTrue(result.memoryDeleted());
        assertEquals(ProjectMemoryRevisionStatus.DELETED, result.revisionStatus());
        ProjectMemoryAdminDetailView detail = fixture.adminService.getDetail(PROJECT, "memory-1");
        assertTrue(detail.deleted());
    }

    @Test
    void shouldRejectSoftDeleteWhenRowVersionConflicts() {
        Fixture fixture = Fixture.enabledGovernance();

        assertThrows(
                ProjectMemoryGovernanceConflictException.class,
                () -> fixture.mutationService.softDelete(
                        new ProjectMemoryAdminMutationService.ProjectMemorySoftDeleteRequest(
                                PROJECT, "memory-1", 99L, "req-delete-conflict", "ignored"
                        )
                )
        );
    }

    @Test
    void shouldKeepMemoryAndSourceSnapshotWhenOriginTaskIsDeleted() {
        Fixture fixture = Fixture.enabledGovernance();
        fixture.store.markSourceReferencesUnavailable("source-1");

        ProjectMemoryAdminDetailView detail = fixture.adminService.getDetail(PROJECT, "memory-1");

        assertEquals(1, fixture.store.listByProject(PROJECT).size());
        assertEquals(HASH, detail.sources().getFirst().sourceContentHash());
        assertEquals("rd-artifact://task-9", detail.sources().getFirst().sourceUri());
        assertFalse(detail.sources().getFirst().originReferencesAvailable());
        assertEquals("redacted summary", detail.sources().getFirst().redactedSummary());
    }

    @Test
    void shouldDisableMutationsWhenTrustedPrincipalProviderIsAbsent() {
        Fixture fixture = Fixture.readOnly();

        assertFalse(fixture.mutationService.mutationsEnabled());
        assertThrows(
                ProjectMemoryMutationDisabledException.class,
                () -> fixture.mutationService.confirm(
                        new ProjectMemoryAdminMutationService.ProjectMemoryConfirmRequest(
                                PROJECT, "memory-1", "revision-1", 2L, 1L, "req-disabled", "ignored"
                        )
                )
        );
    }

    @Nested
    class ProjectMemoryMutationAuthorizerContract {

        @Test
        void shouldFailClosedWhenCapabilityOrProjectScopeIsMissing() {
            RecordingAuditSink auditSink = new RecordingAuditSink();
            FailClosedProjectMemoryMutationAuthorizer authorizer =
                    new FailClosedProjectMemoryMutationAuthorizer(auditSink);
            TrustedOperatorPrincipal principal = new TrustedOperatorPrincipal(
                    "operator-1",
                    Set.of(ProjectMemoryMutationCapability.PROJECT_MEMORY_GOVERN),
                    Set.of(PROJECT)
            );

            assertThrows(
                    ProjectMemoryMutationDeniedException.class,
                    () -> authorizer.authorize(null, PROJECT, ProjectMemoryMutationAction.CONFIRM, "req-null")
            );

            TrustedOperatorPrincipal withoutCapability = new TrustedOperatorPrincipal(
                    "operator-2", Set.of(), Set.of(PROJECT)
            );
            assertThrows(
                    ProjectMemoryMutationDeniedException.class,
                    () -> authorizer.authorize(
                            withoutCapability, PROJECT, ProjectMemoryMutationAction.CONFIRM, "req-cap"
                    )
            );
            assertEquals(1, auditSink.denied.size());

            TrustedOperatorPrincipal crossProject = new TrustedOperatorPrincipal(
                    "operator-3",
                    Set.of(ProjectMemoryMutationCapability.PROJECT_MEMORY_GOVERN),
                    Set.of(OTHER_PROJECT)
            );
            assertThrows(
                    ProjectMemoryMutationDeniedException.class,
                    () -> authorizer.authorize(
                            crossProject, PROJECT, ProjectMemoryMutationAction.SOFT_DELETE, "req-scope"
                    )
            );
            assertEquals(2, auditSink.denied.size());
        }

        @Test
        void shouldAuthorizeProjectScopedGovernActionsAndAuditAllowDeny() {
            RecordingAuditSink auditSink = new RecordingAuditSink();
            FailClosedProjectMemoryMutationAuthorizer authorizer =
                    new FailClosedProjectMemoryMutationAuthorizer(auditSink);
            TrustedOperatorPrincipal principal = new TrustedOperatorPrincipal(
                    "operator-1",
                    Set.of(ProjectMemoryMutationCapability.PROJECT_MEMORY_GOVERN),
                    Set.of(PROJECT)
            );

            authorizer.authorize(principal, PROJECT, ProjectMemoryMutationAction.INVALIDATE, "req-allow");

            assertEquals(1, auditSink.allowed.size());
            assertTrue(auditSink.allowed.getFirst().contains("INVALIDATE"));
            assertTrue(auditSink.allowed.getFirst().contains("operator-1"));
        }

        @Test
        void shouldIgnoreRequestBodyActorAndUseHostPrincipalOnly() {
            Fixture fixture = Fixture.enabledGovernance();
            fixture.principalProvider.setPrincipal(new TrustedOperatorPrincipal(
                    "host-operator",
                    Set.of(ProjectMemoryMutationCapability.PROJECT_MEMORY_GOVERN),
                    Set.of(PROJECT)
            ));

            ProjectMemoryAdminMutationResult result = fixture.mutationService.invalidate(
                    new ProjectMemoryAdminMutationService.ProjectMemoryInvalidateRequest(
                            PROJECT, "memory-1", "revision-1", 2L, 1L, "req-host", "forged-body-actor"
                    )
            );

            assertEquals("host-operator", result.operatorId());
        }
    }

    private static final class Fixture {
        private final InMemoryProjectMemoryStore store = new InMemoryProjectMemoryStore();
        private final InMemoryProjectMemoryAdminQueryPort queryPort = new InMemoryProjectMemoryAdminQueryPort(store);
        private final InMemoryProjectMemoryGovernancePort governancePort = new InMemoryProjectMemoryGovernancePort(store);
        private final RecordingAuditSink auditSink = new RecordingAuditSink();
        private final MutablePrincipalProvider principalProvider = new MutablePrincipalProvider();
        private final ProjectMemoryAdminService adminService = new ProjectMemoryAdminService(queryPort, store);
        private final ProjectMemoryAdminMutationService mutationService;

        private Fixture(boolean mutationsEnabled) {
            FailClosedProjectMemoryMutationAuthorizer authorizer =
                    new FailClosedProjectMemoryMutationAuthorizer(auditSink);
            mutationService = new ProjectMemoryAdminMutationService(
                    mutationsEnabled ? Optional.of(principalProvider) : Optional.empty(),
                    authorizer,
                    governancePort
            );
            seedActiveMemory();
        }

        static Fixture enabledGovernance() {
            Fixture fixture = new Fixture(true);
            fixture.principalProvider.setPrincipal(new TrustedOperatorPrincipal(
                    "operator-1",
                    Set.of(ProjectMemoryMutationCapability.PROJECT_MEMORY_GOVERN),
                    Set.of(PROJECT)
            ));
            return fixture;
        }

        static Fixture readOnly() {
            return new Fixture(false);
        }

        void seedActiveMemory() {
            ProjectMemory memory = store.create(new ProjectMemory(
                    "memory-1", PROJECT, "CODING_AGENT", ProjectMemoryType.PROCEDURAL, "build-command", 1L
            ));
            ProjectMemoryRevision revision = revision("revision-1", memory.memoryId(), 1L, ProjectMemoryRevisionStatus.ACTIVE);
            ProjectMemorySource source = new ProjectMemorySource(
                    "source-1", revision.revisionId(), PROJECT, "task-9", "stage-1", "artifact-1",
                    "rd-artifact://task-9", HASH, "abc123", "extractor-1", "schema-1", "redacted summary"
            );
            store.addRevision(revision);
            store.advanceHead(memory.memoryId(), revision.revisionId(), 1L);
            store.addSource(source);
            queryPort.recordRetrievalAudit(new ProjectMemoryRetrievalAuditEntry(
                    memory.memoryId(), revision.revisionId(), revision.version(), "build command", 3, 1_700_000_000_000L
            ));
        }

        void seedCandidateOnly() {
            ProjectMemory memory = store.create(new ProjectMemory(
                    "memory-2", PROJECT, "CODING_AGENT", ProjectMemoryType.SEMANTIC, "candidate-fact", 1L
            ));
            store.addRevision(revision("revision-candidate", memory.memoryId(), 1L, ProjectMemoryRevisionStatus.CANDIDATE));
        }
    }

    private static ProjectMemoryRevision revision(
            String revisionId,
            String memoryId,
            long version,
            ProjectMemoryRevisionStatus status
    ) {
        return new ProjectMemoryRevision(
                revisionId, memoryId, version, status, "title", "summary", "{}",
                HASH, "schema-1", "", 1L, 1L
        );
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
        private final CopyOnWriteArrayList<String> denied = new CopyOnWriteArrayList<>();

        @Override
        public void recordAllowed(
                TrustedOperatorPrincipal principal,
                String projectId,
                ProjectMemoryMutationAction action,
                String requestId,
                String reason
        ) {
            allowed.add(principal.operatorId() + "|" + projectId + "|" + action + "|" + requestId + "|" + reason);
        }

        @Override
        public void recordDenied(
                TrustedOperatorPrincipal principal,
                String projectId,
                ProjectMemoryMutationAction action,
                String requestId,
                String reason
        ) {
            denied.add(principal.operatorId() + "|" + projectId + "|" + action + "|" + requestId + "|" + reason);
        }
    }
}
