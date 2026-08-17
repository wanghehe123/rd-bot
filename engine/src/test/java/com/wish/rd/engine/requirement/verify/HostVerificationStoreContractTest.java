package com.wish.rd.engine.requirement.verify;

import com.wish.rd.engine.requirement.verify.impl.InMemoryHostVerificationStore;
import com.wish.rd.engine.requirement.verify.model.HostVerificationArtifact;
import com.wish.rd.engine.requirement.verify.model.HostVerificationRun;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStatus;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStep;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStepName;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStepStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Store contract for host verification runs, steps, and artifacts.
 *
 * <p>In-memory is the engine fixture. PostgreSQL must honour the same assertions
 * via {@code PostgresHostVerificationStoreTest}.
 */
class HostVerificationStoreContractTest {

    private static final long NOW = 1_700_000_000_000L;

    static Stream<Arguments> stores() {
        return Stream.of(Arguments.of("in-memory", new InMemoryHostVerificationStore()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void createThenFindReturnsEqualRun(String name, HostVerificationStore store) {
        HostVerificationRun run = created("8001", "9001", 1);
        assertEquals(run, store.create(run), name);
        assertEquals(run, store.find("8001").orElseThrow(), name);
        assertThrows(IllegalStateException.class, () -> store.create(run), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void listByTaskIsOrderedByAttemptNo(String name, HostVerificationStore store) {
        store.create(created("8002", "9001", 2));
        store.create(created("8001", "9001", 1));
        store.create(created("8011", "9002", 1));
        assertEquals(
                List.of("8001", "8002"),
                store.listByTask("9001").stream().map(HostVerificationRun::runId).toList(),
                name
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void transitionCreatedToPreparingSucceeds(String name, HostVerificationStore store) {
        store.create(created("8001", "9001", 1));
        HostVerificationRun next = store.transition(
                "8001",
                HostVerificationStatus.CREATED,
                HostVerificationStatus.PREPARING,
                "",
                "",
                NOW + 5L
        );
        assertEquals(HostVerificationStatus.PREPARING, next.status(), name);
        assertEquals(NOW + 5L, next.startedAtEpochMillis(), name);
        assertEquals(0L, next.finishedAtEpochMillis(), name);
        assertEquals(next, store.find("8001").orElseThrow(), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void transitionWithWrongExpectedThrowsStale(String name, HostVerificationStore store) {
        store.create(created("8001", "9001", 1));
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> store.transition(
                        "8001",
                        HostVerificationStatus.PREPARING,
                        HostVerificationStatus.BUILDING,
                        "",
                        "",
                        NOW
                ),
                name
        );
        assertTrue(exception.getMessage().contains("stale"), exception.getMessage());
        assertEquals(HostVerificationStatus.CREATED, store.find("8001").orElseThrow().status(), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void terminalRunRefusesFurtherTransition(String name, HostVerificationStore store) {
        store.create(created("8001", "9001", 1));
        store.transition("8001", HostVerificationStatus.CREATED, HostVerificationStatus.PREPARING, "", "", NOW + 1L);
        store.transition("8001", HostVerificationStatus.PREPARING, HostVerificationStatus.BUILDING, "", "", NOW + 2L);
        store.transition(
                "8001",
                HostVerificationStatus.BUILDING,
                HostVerificationStatus.STATIC_CHECKING,
                "",
                "",
                NOW + 3L
        );
        HostVerificationRun succeeded = store.transition(
                "8001",
                HostVerificationStatus.STATIC_CHECKING,
                HostVerificationStatus.SUCCEEDED,
                "",
                "",
                NOW + 4L
        );
        assertEquals(HostVerificationStatus.SUCCEEDED, succeeded.status(), name);
        assertEquals(NOW + 4L, succeeded.finishedAtEpochMillis(), name);
        assertThrows(
                IllegalStateException.class,
                () -> store.transition(
                        "8001",
                        HostVerificationStatus.SUCCEEDED,
                        HostVerificationStatus.CANCELLED,
                        "",
                        "",
                        NOW + 5L
                ),
                name
        );
        assertEquals(HostVerificationStatus.SUCCEEDED, store.find("8001").orElseThrow().status(), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void saveStepThenListStepsReturnsBuildAndStatic(String name, HostVerificationStore store) {
        store.create(created("8001", "9001", 1));
        HostVerificationStep build = new HostVerificationStep(
                "8001",
                HostVerificationStepName.BUILD,
                HostVerificationStepStatus.SUCCEEDED,
                List.of("mvn -q test"),
                0,
                12L,
                "6001",
                ""
        );
        HostVerificationStep statik = new HostVerificationStep(
                "8001",
                HostVerificationStepName.STATIC,
                HostVerificationStepStatus.FAILED,
                List.of("npm run typecheck"),
                1,
                3L,
                "",
                "typecheck failed"
        );
        store.saveStep(build);
        store.saveStep(statik);
        assertEquals(List.of(build, statik), store.listSteps("8001"), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void appendArtifactThenListArtifacts(String name, HostVerificationStore store) {
        store.create(created("8001", "9001", 1));
        HostVerificationArtifact artifact = new HostVerificationArtifact(
                "6001",
                "9001",
                "8001",
                "VERIFY_BUILD_LOG",
                "verify-evidence/build.log",
                "s3://rd-qa-evidence/verify/6001",
                "text/plain",
                12L,
                "abc123",
                NOW
        );
        assertEquals(artifact, store.appendArtifact(artifact), name);
        assertEquals(List.of(artifact), store.listArtifacts("8001"), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void duplicateAttemptNoOnSameTaskThrows(String name, HostVerificationStore store) {
        store.create(created("8001", "9001", 1));
        assertThrows(
                IllegalStateException.class,
                () -> store.create(created("8002", "9001", 1)),
                name
        );
        assertEquals(1, store.listByTask("9001").size(), name);
        store.create(created("8002", "9002", 1));
        assertEquals("8002", store.find("8002").orElseThrow().runId(), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void deleteByTaskRemovesArtifactsAndLeavesOtherTasks(String name, HostVerificationStore store) {
        store.create(created("8001", "9001", 1));
        store.create(created("8011", "9002", 1));
        store.appendArtifact(new HostVerificationArtifact(
                "6001",
                "9001",
                "8001",
                "VERIFY_BUILD_LOG",
                "verify-evidence/build.log",
                "s3://rd-qa-evidence/verify/6001",
                "text/plain",
                12L,
                "abc123",
                NOW
        ));
        store.appendArtifact(new HostVerificationArtifact(
                "6011",
                "9002",
                "8011",
                "VERIFY_STATIC_LOG",
                "verify-evidence/static.log",
                "file:///tmp/verify-6011.log",
                "text/plain",
                4L,
                "def456",
                NOW
        ));

        assertEquals(1, store.deleteByTask("9001"), name);
        assertTrue(store.listArtifacts("8001").isEmpty(), name);
        assertEquals(1, store.listByTask("9001").size(), name);
        assertEquals(1, store.listArtifacts("8011").size(), name);
        assertEquals(0, store.deleteByTask("9001"), name);
    }

    private static HostVerificationRun created(String runId, String taskId, int attemptNo) {
        return new HostVerificationRun(
                runId,
                taskId,
                "7001",
                "",
                attemptNo,
                HostVerificationStatus.CREATED,
                false,
                "",
                "",
                0,
                NOW,
                0L,
                0L
        );
    }
}
