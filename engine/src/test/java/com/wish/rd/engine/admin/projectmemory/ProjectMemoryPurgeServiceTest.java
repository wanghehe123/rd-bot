package com.wish.rd.engine.admin.projectmemory;

import com.wish.rd.engine.admin.projectmemory.impl.InMemoryProjectMemoryPurgeConfirmTokenStore;

import com.wish.rd.engine.admin.projectmemory.impl.FailClosedProjectMemoryMutationAuthorizer;

import com.wish.rd.engine.admin.projectmemory.model.ProjectMemoryPurgeExecuteResult;
import com.wish.rd.engine.admin.projectmemory.model.ProjectMemoryPurgePreviewResult;
import com.wish.rd.rag.project.memory.ProjectMemoryPurgePort;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryAdminQueryPort;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryGovernancePort;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryPurgePort;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryStore;
import com.wish.rd.rag.project.memory.model.ProjectMemory;
import com.wish.rd.rag.project.memory.model.ProjectMemoryPurgeCounts;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMemoryPurgeServiceTest {

    private static final String PROJECT = "101";
    private static final String OTHER_PROJECT = "202";
    private static final String HASH = "a".repeat(64);
    private static final String REASON = "retire stale canary project memory";

    @Test
    void shouldPreviewWithTrustedOperatorReasonProjectScopeAndExpectedCounts() {
        Fixture fixture = Fixture.enabledPurge();
        fixture.seedActiveMemory();

        ProjectMemoryPurgePreviewResult preview = fixture.purgeService.preview(
                new ProjectMemoryPurgeService.ProjectMemoryPurgePreviewRequest(
                        PROJECT, REASON, "req-preview", "forged-body-actor"
                )
        );

        assertEquals(PROJECT, preview.projectId());
        assertEquals("purge-operator", preview.operatorId());
        assertEquals(REASON, preview.reason());
        assertEquals(1, preview.expectedCounts().memoryCount());
        assertEquals(1, preview.expectedCounts().revisionCount());
        assertEquals(1, preview.expectedCounts().sourceCount());
        assertEquals(3, preview.expectedCounts().totalRowCount());
        assertFalse(preview.confirmToken().isBlank());
        assertTrue(preview.confirmTokenExpiresAtEpochMillis() > fixture.now.get());
        assertEquals(1, fixture.auditSink.allowed.stream().filter(entry -> entry.contains("PURGE_PREVIEW")).count());
    }

    @Test
    void shouldExecuteAfterConfirmTokenAndReAuthorizeWithMatchingActualCounts() {
        Fixture fixture = Fixture.enabledPurge();
        fixture.seedActiveMemory();

        ProjectMemoryPurgePreviewResult preview = fixture.purgeService.preview(
                new ProjectMemoryPurgeService.ProjectMemoryPurgePreviewRequest(
                        PROJECT, REASON, "req-preview", "ignored"
                )
        );
        ProjectMemoryPurgeExecuteResult executed = fixture.purgeService.execute(
                new ProjectMemoryPurgeService.ProjectMemoryPurgeExecuteRequest(
                        PROJECT, REASON, preview.confirmToken(), "req-execute", "ignored"
                )
        );

        assertEquals("purge-operator", executed.operatorId());
        assertEquals(REASON, executed.reason());
        assertEquals(3, executed.expectedCounts().totalRowCount());
        assertEquals(3, executed.actualCounts().totalRowCount());
        assertTrue(fixture.store.listByProject(PROJECT).isEmpty());
        assertEquals(2, fixture.auditSink.allowed.stream().filter(entry -> entry.contains("PURGE_")).count());
    }

    @Test
    void shouldRejectExpiredConfirmTokenBeforeReAuthorize() {
        Fixture fixture = Fixture.enabledPurge();
        fixture.seedActiveMemory();
        ProjectMemoryPurgePreviewResult preview = fixture.purgeService.preview(
                new ProjectMemoryPurgeService.ProjectMemoryPurgePreviewRequest(
                        PROJECT, REASON, "req-preview", "ignored"
                )
        );
        fixture.now.addAndGet(ProjectMemoryPurgeService.DEFAULT_CONFIRM_TOKEN_TTL_MILLIS + 1L);

        assertThrows(
                ProjectMemoryPurgeConfirmTokenExpiredException.class,
                () -> fixture.purgeService.execute(
                        new ProjectMemoryPurgeService.ProjectMemoryPurgeExecuteRequest(
                                PROJECT, REASON, preview.confirmToken(), "req-expired", "ignored"
                        )
                )
        );
        assertEquals(1, fixture.store.listByProject(PROJECT).size());
        assertEquals(0, fixture.auditSink.allowed.stream().filter(entry -> entry.contains("PURGE_EXECUTE")).count());
    }

    @Test
    void shouldAuditAllowAndDenyForPurgeAuthorization() {
        RecordingAuditSink auditSink = new RecordingAuditSink();
        FailClosedProjectMemoryMutationAuthorizer authorizer =
                new FailClosedProjectMemoryMutationAuthorizer(auditSink);
        TrustedOperatorPrincipal crossProject = new TrustedOperatorPrincipal(
                "purge-operator",
                Set.of(ProjectMemoryMutationCapability.PROJECT_MEMORY_PURGE),
                Set.of(OTHER_PROJECT)
        );

        assertThrows(
                ProjectMemoryMutationDeniedException.class,
                () -> authorizer.authorize(
                        crossProject, PROJECT, ProjectMemoryMutationAction.PURGE_PREVIEW, "req-deny"
                )
        );
        assertEquals(1, auditSink.denied.size());
        assertTrue(auditSink.denied.getFirst().contains("PURGE_PREVIEW"));
    }

    @Test
    void shouldRejectCrossProjectPurgePreview() {
        Fixture fixture = Fixture.enabledPurge();
        fixture.seedActiveMemory();
        fixture.principalProvider.setPrincipal(new TrustedOperatorPrincipal(
                "purge-operator",
                Set.of(ProjectMemoryMutationCapability.PROJECT_MEMORY_PURGE),
                Set.of(OTHER_PROJECT)
        ));

        assertThrows(
                ProjectMemoryMutationDeniedException.class,
                () -> fixture.purgeService.preview(
                        new ProjectMemoryPurgeService.ProjectMemoryPurgePreviewRequest(
                                PROJECT, REASON, "req-cross", "ignored"
                        )
                )
        );
        assertEquals(1, fixture.store.listByProject(PROJECT).size());
    }

    @Test
    void shouldRejectExecuteWhenConfirmTokenDoesNotSubstitutePurgePermission() {
        Fixture fixture = Fixture.enabledPurge();
        fixture.seedActiveMemory();
        ProjectMemoryPurgePreviewResult preview = fixture.purgeService.preview(
                new ProjectMemoryPurgeService.ProjectMemoryPurgePreviewRequest(
                        PROJECT, REASON, "req-preview", "ignored"
                )
        );
        fixture.principalProvider.setPrincipal(new TrustedOperatorPrincipal(
                "purge-operator",
                Set.of(ProjectMemoryMutationCapability.PROJECT_MEMORY_GOVERN),
                Set.of(PROJECT)
        ));

        assertThrows(
                ProjectMemoryMutationDeniedException.class,
                () -> fixture.purgeService.execute(
                        new ProjectMemoryPurgeService.ProjectMemoryPurgeExecuteRequest(
                                PROJECT, REASON, preview.confirmToken(), "req-no-purge-cap", "ignored"
                        )
                )
        );
        assertEquals(1, fixture.store.listByProject(PROJECT).size());
    }

    @Nested
    class OrdinaryDeleteIsolation {

        @Test
        void shouldNotInvokePurgePortWhenOriginTaskIsDeleted() {
            Fixture fixture = Fixture.enabledPurge();
            fixture.seedActiveMemory();
            fixture.store.markSourceReferencesUnavailable("source-1");

            assertEquals(0, fixture.recordingPurgePort.previewCalls.get());
            assertEquals(0, fixture.recordingPurgePort.executeCalls.get());
            assertEquals(1, fixture.store.listByProject(PROJECT).size());
        }

        @Test
        void shouldNotInvokePurgePortWhenMemoryIsSoftDeleted() {
            Fixture fixture = Fixture.enabledPurge();
            fixture.seedActiveMemory();
            fixture.principalProvider.setPrincipal(new TrustedOperatorPrincipal(
                    "purge-operator",
                    Set.of(
                            ProjectMemoryMutationCapability.PROJECT_MEMORY_PURGE,
                            ProjectMemoryMutationCapability.PROJECT_MEMORY_GOVERN
                    ),
                    Set.of(PROJECT)
            ));
            fixture.mutationService.softDelete(
                    new ProjectMemoryAdminMutationService.ProjectMemorySoftDeleteRequest(
                            PROJECT, "memory-1", 2L, "req-soft-delete", "ignored"
                    )
            );

            assertEquals(0, fixture.recordingPurgePort.executeCalls.get());
            assertEquals(1, fixture.store.listByProject(PROJECT).size());
            ProjectMemory memory = fixture.store.find("memory-1").orElseThrow();
            assertTrue(memory.deleted());
        }
    }

    @Test
    void shouldDisablePurgeWhenTrustedPrincipalProviderIsAbsent() {
        Fixture fixture = Fixture.readOnly();

        assertFalse(fixture.purgeService.purgeEnabled());
        assertThrows(
                ProjectMemoryMutationDisabledException.class,
                () -> fixture.purgeService.preview(
                        new ProjectMemoryPurgeService.ProjectMemoryPurgePreviewRequest(
                                PROJECT, REASON, "req-disabled", "ignored"
                        )
                )
        );
    }

    private static final class Fixture {
        private final InMemoryProjectMemoryStore store = new InMemoryProjectMemoryStore();
        private final InMemoryProjectMemoryAdminQueryPort queryPort = new InMemoryProjectMemoryAdminQueryPort(store);
        private final RecordingPurgePort recordingPurgePort = new RecordingPurgePort(
                new InMemoryProjectMemoryPurgePort(store, queryPort)
        );
        private final RecordingAuditSink auditSink = new RecordingAuditSink();
        private final MutablePrincipalProvider principalProvider = new MutablePrincipalProvider();
        private final AtomicLong now = new AtomicLong(1_700_000_000_000L);
        private final ProjectMemoryPurgeService purgeService;
        private final ProjectMemoryAdminMutationService mutationService;

        private Fixture(boolean purgeEnabled) {
            FailClosedProjectMemoryMutationAuthorizer authorizer =
                    new FailClosedProjectMemoryMutationAuthorizer(auditSink);
            InMemoryProjectMemoryGovernancePort governancePort = new InMemoryProjectMemoryGovernancePort(store);
            mutationService = new ProjectMemoryAdminMutationService(
                    purgeEnabled ? Optional.of(principalProvider) : Optional.empty(),
                    authorizer,
                    governancePort
            );
            purgeService = new ProjectMemoryPurgeService(
                    purgeEnabled ? Optional.of(principalProvider) : Optional.empty(),
                    authorizer,
                    recordingPurgePort,
                    new InMemoryProjectMemoryPurgeConfirmTokenStore(),
                    now::get
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

        static Fixture readOnly() {
            return new Fixture(false);
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
            queryPort.recordRetrievalAudit(new ProjectMemoryRetrievalAuditEntry(
                    memory.memoryId(), revision.revisionId(), revision.version(), "build command", 3, now.get()
            ));
        }
    }

    private static final class RecordingPurgePort implements ProjectMemoryPurgePort {
        private final ProjectMemoryPurgePort delegate;
        private final AtomicLong previewCalls = new AtomicLong();
        private final AtomicLong executeCalls = new AtomicLong();

        private RecordingPurgePort(ProjectMemoryPurgePort delegate) {
            this.delegate = delegate;
        }

        @Override
        public ProjectMemoryPurgeCounts previewCounts(String projectId) {
            previewCalls.incrementAndGet();
            return delegate.previewCounts(projectId);
        }

        @Override
        public ProjectMemoryPurgeCounts executePurge(String projectId) {
            executeCalls.incrementAndGet();
            return delegate.executePurge(projectId);
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
