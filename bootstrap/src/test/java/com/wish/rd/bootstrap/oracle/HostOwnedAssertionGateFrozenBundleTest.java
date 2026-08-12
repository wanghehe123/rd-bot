package com.wish.rd.bootstrap.oracle;

import com.wish.rd.engine.oracle.HostAssertionOracle;
import com.wish.rd.engine.oracle.AssertionRunnerPort;
import com.wish.rd.engine.oracle.impl.FileAssertionRunner;
import com.wish.rd.engine.oracle.impl.InMemoryHostAssertionBundleStore;
import com.wish.rd.engine.oracle.model.AssertionResult;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionSpecBundle;
import com.wish.rd.engine.oracle.model.AssertionType;
import com.wish.rd.engine.oracle.model.FrozenAssertionBundle;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.oracle.HostVerifierWorkspaceFactory;
import com.wish.rd.exec.repair.oracle.model.HostVerifierWorkspace;
import com.wish.rd.exec.repair.oracle.model.HostVerifierWorkspaceRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostOwnedAssertionGateFrozenBundleTest {

    @TempDir
    Path workspace;

    @Test
    void shouldRejectQaResultWithoutHostEchoWhenFrozenBundlesExist() {
        InMemoryHostAssertionBundleStore store = new InMemoryHostAssertionBundleStore();
        store.saveIfAbsent(bundle("CURRENT", "current.txt"));
        store.saveIfAbsent(bundle("REGRESSION", "regression.txt"));
        HostVerifierWorkspaceFactory workspaceFactory = (command, frozen) ->
                new HostVerifierWorkspace(workspace, "", Map.of());
        HostOwnedAssertionGate gate = new HostOwnedAssertionGate(
                new HostAssertionOracle(Map.of(AssertionType.FILE_EXISTS, new FileAssertionRunner())),
                store,
                workspaceFactory
        );

        List<String> errors = gate.validate(strictQaResult(), "task-1", "stage-1", command());

        assertTrue(errors.stream().anyMatch(error -> error.contains("hostAssertionResults is required")), errors::toString);
    }

    @Test
    void shouldRejectAgentControlledWorkspaceAndExecutableSpecs() {
        HostOwnedAssertionGate gate = new HostOwnedAssertionGate(
                new HostAssertionOracle(Map.of(AssertionType.FILE_EXISTS, new FileAssertionRunner()))
        );

        List<String> errors = gate.validate("""
                {
                  "hostAssertionWorkspace":"/tmp/agent-selected",
                  "hostAssertionBaseUrl":"http://agent.invalid",
                  "hostAssertionBundle":{"specs":[]}
                }
                """, "task-1", "stage-1", command());

        assertTrue(errors.stream().anyMatch(error -> error.contains("hostAssertionWorkspace")), errors::toString);
        assertTrue(errors.stream().anyMatch(error -> error.contains("hostAssertionBaseUrl")), errors::toString);
        assertTrue(errors.stream().anyMatch(error -> error.contains("hostAssertionBundle")), errors::toString);
    }

    @Test
    void shouldRejectMismatchedFrozenHash() {
        InMemoryHostAssertionBundleStore store = frozenStore();
        HostOwnedAssertionGate gate = gate(store, (command, frozen) ->
                new HostVerifierWorkspace(workspace, "", Map.of()));
        String regressionHash = store.find("task-1", "stage-1", "REGRESSION").orElseThrow().contentHash();

        List<String> errors = gate.validate(
                hostEchoResult("sha256:" + "0".repeat(64), regressionHash,
                        "qa-evidence/commands/current.log", "qa-evidence/commands/regression.log"),
                "task-1",
                "stage-1",
                command()
        );

        assertTrue(errors.stream().anyMatch(error -> error.contains("contentHash does not match")), errors::toString);
    }

    @Test
    void shouldRejectEchoEvidenceFromTheOtherScope() {
        InMemoryHostAssertionBundleStore store = frozenStore();
        HostOwnedAssertionGate gate = gate(store, (command, frozen) ->
                new HostVerifierWorkspace(workspace, "", Map.of()));
        String currentHash = store.find("task-1", "stage-1", "CURRENT").orElseThrow().contentHash();
        String regressionHash = store.find("task-1", "stage-1", "REGRESSION").orElseThrow().contentHash();

        List<String> errors = gate.validate(
                hostEchoResult(currentHash, regressionHash,
                        "qa-evidence/commands/regression.log", "qa-evidence/commands/regression.log"),
                "task-1",
                "stage-1",
                command()
        );

        assertTrue(errors.stream().anyMatch(error -> error.contains("CURRENT] evidence")), errors::toString);
        assertTrue(errors.stream().anyMatch(error -> error.contains("not referenced by CURRENT")), errors::toString);
    }

    @Test
    void shouldCreateDistinctHostVerifierWorkspacesForBothScopes() throws Exception {
        InMemoryHostAssertionBundleStore store = new InMemoryHostAssertionBundleStore();
        store.saveIfAbsent(bundle("CURRENT", "marker.txt"));
        store.saveIfAbsent(bundle("REGRESSION", "marker.txt"));
        Path currentWorkspace = Files.createDirectories(workspace.resolve("current"));
        Path regressionWorkspace = Files.createDirectories(workspace.resolve("regression"));
        Files.writeString(currentWorkspace.resolve("marker.txt"), "current");
        Files.writeString(regressionWorkspace.resolve("marker.txt"), "regression");
        List<String> requestedScopes = new ArrayList<>();
        List<Path> verifierRoots = new ArrayList<>();
        HostOwnedAssertionGate gate = gate(store, (command, request) -> {
            requestedScopes.add(request.scope());
            Path root = "CURRENT".equals(request.scope()) ? currentWorkspace : regressionWorkspace;
            verifierRoots.add(root);
            return new HostVerifierWorkspace(root, "", Map.of());
        });
        String currentHash = store.find("task-1", "stage-1", "CURRENT").orElseThrow().contentHash();
        String regressionHash = store.find("task-1", "stage-1", "REGRESSION").orElseThrow().contentHash();

        List<String> errors = gate.validate(
                hostEchoResult(currentHash, regressionHash,
                        "qa-evidence/commands/current.log", "qa-evidence/commands/regression.log"),
                "task-1",
                "stage-1",
                command()
        );

        assertTrue(errors.isEmpty(), errors::toString);
        assertEquals(List.of("CURRENT", "REGRESSION"), requestedScopes);
        assertEquals(2, new HashSet<>(verifierRoots).size());
    }

    @Test
    void shouldCloseEveryCleanWorkspaceWhenAHostAssertionFails() {
        InMemoryHostAssertionBundleStore store = frozenStore();
        AtomicInteger closedWorkspaces = new AtomicInteger();
        List<HostVerifierWorkspaceRequest> requests = new ArrayList<>();
        HostOwnedAssertionGate gate = gate(store, (command, request) -> {
            requests.add(request);
            return new HostVerifierWorkspace(
                    workspace,
                    "",
                    Map.of(),
                    () -> closedWorkspaces.incrementAndGet()
            );
        });
        String currentHash = store.find("task-1", "stage-1", "CURRENT").orElseThrow().contentHash();
        String regressionHash = store.find("task-1", "stage-1", "REGRESSION").orElseThrow().contentHash();

        List<String> errors = gate.validate(
                hostEchoResult(currentHash, regressionHash,
                        "qa-evidence/commands/current.log", "qa-evidence/commands/regression.log"),
                "task-1",
                "stage-1",
                command()
        );

        assertTrue(errors.stream().anyMatch(error -> error.contains("FAILED")), errors::toString);
        assertEquals(List.of("CURRENT", "REGRESSION"), requests.stream()
                .map(HostVerifierWorkspaceRequest::scope)
                .toList());
        assertTrue(requests.stream().noneMatch(HostVerifierWorkspaceRequest::runtimeRequired));
        assertEquals(2, closedWorkspaces.get());
    }

    @Test
    void shouldRequireIndependentRuntimesForHttpAndBrowserScopes() {
        InMemoryHostAssertionBundleStore store = new InMemoryHostAssertionBundleStore();
        store.saveIfAbsent(bundle("CURRENT", "/health", AssertionType.HTTP_STATUS));
        store.saveIfAbsent(bundle("REGRESSION", "#save", AssertionType.BROWSER_DOM));
        AssertionRunnerPort passingRunner = (spec, context) -> AssertionResult.passed(
                spec.id(), spec.assertionType(), "passed", List.of()
        );
        AtomicInteger closedWorkspaces = new AtomicInteger();
        List<HostVerifierWorkspaceRequest> requests = new ArrayList<>();
        HostOwnedAssertionGate gate = new HostOwnedAssertionGate(
                new HostAssertionOracle(Map.of(
                        AssertionType.HTTP_STATUS, passingRunner,
                        AssertionType.BROWSER_DOM, passingRunner
                )),
                store,
                (command, request) -> {
                    requests.add(request);
                    return new HostVerifierWorkspace(
                            workspace,
                            "http://127.0.0.1:1",
                            Map.of(),
                            () -> closedWorkspaces.incrementAndGet()
                    );
                }
        );
        String currentHash = store.find("task-1", "stage-1", "CURRENT").orElseThrow().contentHash();
        String regressionHash = store.find("task-1", "stage-1", "REGRESSION").orElseThrow().contentHash();

        List<String> errors = gate.validate(
                hostEchoResult(currentHash, regressionHash,
                        "qa-evidence/commands/current.log", "qa-evidence/commands/regression.log"),
                "task-1",
                "stage-1",
                command()
        );

        assertTrue(errors.isEmpty(), errors::toString);
        assertEquals(List.of("CURRENT", "REGRESSION"), requests.stream()
                .map(HostVerifierWorkspaceRequest::scope)
                .toList());
        assertTrue(requests.stream().allMatch(HostVerifierWorkspaceRequest::runtimeRequired));
        assertEquals(2, closedWorkspaces.get());
    }

    private static InMemoryHostAssertionBundleStore frozenStore() {
        InMemoryHostAssertionBundleStore store = new InMemoryHostAssertionBundleStore();
        store.saveIfAbsent(bundle("CURRENT", "current.txt"));
        store.saveIfAbsent(bundle("REGRESSION", "regression.txt"));
        return store;
    }

    private static HostOwnedAssertionGate gate(
            InMemoryHostAssertionBundleStore store,
            HostVerifierWorkspaceFactory workspaceFactory
    ) {
        return new HostOwnedAssertionGate(
                new HostAssertionOracle(Map.of(AssertionType.FILE_EXISTS, new FileAssertionRunner())),
                store,
                workspaceFactory
        );
    }

    private static FrozenAssertionBundle bundle(String scope, String target) {
        return bundle(scope, target, AssertionType.FILE_EXISTS);
    }

    private static FrozenAssertionBundle bundle(String scope, String target, AssertionType type) {
        boolean httpStatus = type == AssertionType.HTTP_STATUS;
        AssertionSpec spec = new AssertionSpec(
                scope.toLowerCase() + "-file",
                "criteria-1",
                List.of(),
                "",
                httpStatus ? "GET /health" : "",
                type,
                target,
                httpStatus ? "eq" : "exists",
                httpStatus ? "200" : "",
                "",
                List.of("qa-evidence/commands/" + scope.toLowerCase() + ".log"),
                1_000L,
                ""
        );
        return new FrozenAssertionBundle("task-1", "stage-1", scope, AssertionSpecBundle.freeze(List.of(spec)), 1L);
    }

    private static RepairJobCommand command() {
        return new RepairJobCommand(
                "task-1-qa",
                "task-1-qa",
                "",
                "QA",
                "verify",
                "https://example.invalid/org/repo.git",
                "org",
                "repo",
                "main",
                "host-verify",
                Map.of("workflowTaskId", "task-1", "stageRunId", "stage-1"),
                Map.of("repositoryDeliveryMode", "LOCAL_ONLY"),
                List.of()
        );
    }

    private static String strictQaResult() {
        return """
                {
                  "status": "PASSED",
                  "summary": "all checks passed",
                  "failureCategory": "NONE",
                  "retryRecommendation": "NONE",
                  "browserValidation": {
                    "required": false,
                    "performed": false,
                    "decisionSource": "NOT_APPLICABLE",
                    "baseUrl": "",
                    "browser": "chromium",
                    "viewports": []
                  },
                  "acceptanceResults": [
                    {
                      "criteria": "current",
                      "scope": "CURRENT",
                      "command": "test current",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 1,
                      "logArtifactId": "qa-evidence/commands/current.log",
                      "evidenceArtifactIds": ["qa-evidence/commands/current.log"]
                    },
                    {
                      "criteria": "regression",
                      "scope": "REGRESSION",
                      "command": "test regression",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 1,
                      "logArtifactId": "qa-evidence/commands/regression.log",
                      "evidenceArtifactIds": ["qa-evidence/commands/regression.log"]
                    }
                  ],
                  "evidenceManifestArtifactId": "qa-evidence/manifest.json"
                }
                """;
    }

    private static String hostEchoResult(
            String currentHash,
            String regressionHash,
            String currentEvidence,
            String regressionEvidence
    ) {
        String base = strictQaResult().strip();
        return base.substring(0, base.length() - 1) + """
                  ,
                  "hostAssertionResults": [
                    {
                      "scope": "CURRENT",
                      "contentHash": "%s",
                      "evidenceArtifactIds": ["%s"]
                    },
                    {
                      "scope": "REGRESSION",
                      "contentHash": "%s",
                      "evidenceArtifactIds": ["%s"]
                    }
                  ]
                }
                """.formatted(currentHash, currentEvidence, regressionHash, regressionEvidence);
    }
}
