package com.wish.rd.bootstrap.oracle;

import com.sun.net.httpserver.HttpServer;
import com.wish.rd.bootstrap.oracle.impl.CleanHostVerifierWorkspaceFactory;
import com.wish.rd.exec.repair.docker.RepairWorkspaceFactory;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort;
import com.wish.rd.exec.repair.docker.model.RepairWorkspace;
import com.wish.rd.exec.repair.execution.model.RepairInputAttachment;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.oracle.model.HostVerifierWorkspace;
import com.wish.rd.exec.repair.oracle.model.HostVerifierWorkspaceRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CleanHostVerifierWorkspaceFactoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldReplayOnlyVerifiedCandidatePatchInFreshHostWorkspace() throws Exception {
        byte[] patch = "diff --git a/a.txt b/a.txt\n".getBytes(StandardCharsets.UTF_8);
        CapturingRepository repository = new CapturingRepository();
        CleanHostVerifierWorkspaceFactory factory = new CleanHostVerifierWorkspaceFactory(
                new RepairWorkspaceFactory(temporaryDirectory.resolve("workspaces"), "{}"),
                repository
        );

        HostVerifierWorkspace workspace = factory.create(
                command(patch, sha256(patch)),
                new HostVerifierWorkspaceRequest("task-1", "stage-1", "CURRENT")
        );

        assertTrue(Files.isRegularFile(workspace.workspaceRoot().resolve("replayed.txt")));
        assertEquals("", workspace.baseUrl());
        assertEquals("CURRENT", workspace.attributes().get("hostAssertionScope"));
        assertEquals(1, repository.command.attachments().size());
        assertEquals("candidate-patch.diff", repository.command.attachments().getFirst().filename());
        assertTrue(Boolean.parseBoolean(repository.command.policyJson().get("applyCandidatePatch")));
        assertEquals("LOCAL_ONLY", repository.command.policyJson().get("repositoryDeliveryMode"));
        assertFalse(repository.command.attachments().stream()
                .anyMatch(attachment -> attachment.filename().equals("untrusted.txt")));
    }

    @Test
    void shouldStartPatchedReplayRuntimeInsteadOfConfiguredService() throws Exception {
        byte[] patch = "diff --git a/a.txt b/a.txt\n".getBytes(StandardCharsets.UTF_8);
        AtomicInteger staleServiceRequests = new AtomicInteger();
        HttpServer staleService = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        staleService.createContext("/", exchange -> {
            staleServiceRequests.incrementAndGet();
            byte[] body = "{\"version\":\"old\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        staleService.start();

        HostVerifierWorkspace workspace = null;
        try {
            int configuredPort = staleService.getAddress().getPort();
            CapturingRepository repository = CapturingRepository.withRuntimeServer();
            CleanHostVerifierWorkspaceFactory factory = new CleanHostVerifierWorkspaceFactory(
                    new RepairWorkspaceFactory(temporaryDirectory.resolve("runtime-workspaces"), "{}"),
                    repository,
                    new CleanHostVerifierWorkspaceFactory.RuntimeConfiguration(3_000L, 25L, 1_000L)
            );

            workspace = factory.create(
                    command(
                            patch,
                            sha256(patch),
                            configuredPort,
                            "node server.mjs --port " + configuredPort
                    ),
                    new HostVerifierWorkspaceRequest("task-1", "stage-1", "CURRENT", true)
            );

            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(workspace.baseUrl()).resolve("/state"))
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            long runtimeShellPid = Long.parseLong(workspace.attributes().get("hostVerifierRuntimePid"));
            long runtimeNodePid = Long.parseLong(Files.readString(workspace.workspaceRoot().resolve("server.pid")).strip());

            assertEquals(200, response.statusCode());
            assertEquals("{\"version\":\"patched\"}", response.body());
            assertNotEquals("http://127.0.0.1:" + configuredPort, workspace.baseUrl());
            assertEquals(0, staleServiceRequests.get());

            workspace.close();
            workspace = null;
            assertEventuallyStopped(runtimeShellPid);
            assertEventuallyStopped(runtimeNodePid);
        } finally {
            if (workspace != null) {
                workspace.close();
            }
            staleService.stop(0);
        }
    }

    @Test
    void shouldStopTimedOutRuntimeBeforeReturningFailure() throws Exception {
        byte[] patch = "diff --git a/a.txt b/a.txt\n".getBytes(StandardCharsets.UTF_8);
        Path pidFile = temporaryDirectory.resolve("timed-out-runtime.pid");
        CleanHostVerifierWorkspaceFactory factory = new CleanHostVerifierWorkspaceFactory(
                new RepairWorkspaceFactory(temporaryDirectory.resolve("timeout-workspaces"), "{}"),
                new CapturingRepository(),
                new CleanHostVerifierWorkspaceFactory.RuntimeConfiguration(500L, 25L, 1_000L)
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> factory.create(
                        command(
                                patch,
                                sha256(patch),
                                18_080,
                                "echo $$ > " + shellQuote(pidFile) + "; sleep 30"
                        ),
                        new HostVerifierWorkspaceRequest("task-1", "stage-1", "CURRENT", true)
                )
        );

        assertTrue(failure.getMessage().contains("timed out"));
        assertTrue(Files.isRegularFile(pidFile));
        assertEventuallyStopped(Long.parseLong(Files.readString(pidFile).strip()));
    }

    @Test
    void shouldReportEarlyRuntimeExitBeforeReadiness() throws Exception {
        byte[] patch = "diff --git a/a.txt b/a.txt\n".getBytes(StandardCharsets.UTF_8);
        CleanHostVerifierWorkspaceFactory factory = new CleanHostVerifierWorkspaceFactory(
                new RepairWorkspaceFactory(temporaryDirectory.resolve("exit-workspaces"), "{}"),
                new CapturingRepository(),
                new CleanHostVerifierWorkspaceFactory.RuntimeConfiguration(500L, 25L, 1_000L)
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> factory.create(
                        command(patch, sha256(patch), 18_080, "exit 17"),
                        new HostVerifierWorkspaceRequest("task-1", "stage-1", "CURRENT", true)
                )
        );

        assertTrue(failure.getMessage().contains("exited before readiness"));
        assertTrue(failure.getMessage().contains("17"));
    }

    @Test
    void shouldRejectCandidatePatchWhenHandoffDigestDoesNotMatch() {
        byte[] patch = "diff --git a/a.txt b/a.txt\n".getBytes(StandardCharsets.UTF_8);
        CapturingRepository repository = new CapturingRepository();
        CleanHostVerifierWorkspaceFactory factory = new CleanHostVerifierWorkspaceFactory(
                new RepairWorkspaceFactory(temporaryDirectory.resolve("workspaces"), "{}"),
                repository
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> factory.create(
                        command(patch, "sha256:" + "0".repeat(64)),
                        new HostVerifierWorkspaceRequest("task-1", "stage-1", "REGRESSION")
                )
        );

        assertTrue(failure.getMessage().contains("digest"));
        assertEquals(0, repository.calls);
    }

    private static RepairJobCommand command(byte[] patch, String patchDigest) {
        return command(patch, patchDigest, 18_080, "npm run start");
    }

    private static RepairJobCommand command(
            byte[] patch,
            String patchDigest,
            int configuredPort,
            String startCommand
    ) {
        String manifest = """
                [{
                  "sourceRole":"CODING_AGENT",
                  "targetRole":"QA_AGENT",
                  "path":"/work/input/attachments/candidate-patch.diff",
                  "sha256":"%s",
                  "bytes":%d,
                  "applied":true
                }]
                """.formatted(patchDigest, patch.length);
        return new RepairJobCommand(
                "repair-task-1-qa",
                "task-1-qa",
                "",
                "QA",
                "verify",
                "https://example.invalid/acme/repo.git",
                "acme",
                "repo",
                "main",
                "host-verify",
                Map.of(
                        "workflowTaskId", "task-1",
                        "stageRunId", "stage-1",
                        "agentRole", "QA_AGENT",
                        "upstreamHandoffManifestJson", manifest,
                        "qaTaskOverrideJson", """
                                {"mode":"REQUIRED","baseUrl":"http://127.0.0.1:%d","startCommand":"%s","healthPath":"/health","allowedHosts":["127.0.0.1"],"regressionCommands":[]}
                                """.formatted(configuredPort, startCommand)
                ),
                Map.of("repositoryDeliveryMode", "LOCAL_ONLY"),
                List.of(
                        new RepairInputAttachment("candidate-patch.diff", "text/x-diff", patch),
                        new RepairInputAttachment("untrusted.txt", "text/plain", "ignore".getBytes(StandardCharsets.UTF_8))
                )
        );
    }

    private static String sha256(byte[] value) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static void assertEventuallyStopped(long pid) throws InterruptedException {
        for (int attempt = 0; attempt < 40; attempt++) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                Thread.sleep(25L);
                continue;
            }
            return;
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                "Host verifier process must be stopped: " + pid);
    }

    private static String shellQuote(Path value) {
        return "'" + value.toAbsolutePath().toString().replace("'", "'\"'\"'") + "'";
    }

    private static final class CapturingRepository implements RepairWorkspaceRepositoryPort {
        private static final String RUNTIME_SERVER = """
                const fs = require('node:fs');
                const http = require('node:http');
                const portIndex = process.argv.indexOf('--port');
                const port = Number(portIndex >= 0 ? process.argv[portIndex + 1] : process.env.PORT);
                fs.writeFileSync('server.pid', String(process.pid));
                http.createServer((request, response) => {
                  response.writeHead(200, {'content-type': 'application/json'});
                  response.end(JSON.stringify({version: 'patched'}));
                }).listen(port, '127.0.0.1');
                """;

        private int calls;
        private RepairJobCommand command;
        private final boolean runtimeServer;

        private CapturingRepository() {
            this(false);
        }

        private CapturingRepository(boolean runtimeServer) {
            this.runtimeServer = runtimeServer;
        }

        private static CapturingRepository withRuntimeServer() {
            return new CapturingRepository(true);
        }

        @Override
        public RepositoryOperationResult prepare(RepairJobCommand command, RepairWorkspace workspace) throws java.io.IOException {
            calls++;
            this.command = command;
            Files.writeString(workspace.repoDirectory().resolve("replayed.txt"), "host replay");
            if (runtimeServer) {
                Files.writeString(workspace.repoDirectory().resolve("server.mjs"), RUNTIME_SERVER, StandardCharsets.UTF_8);
            }
            return new RepositoryOperationResult(Map.of("prepared", "true"));
        }

        @Override
        public RepositoryOperationResult publish(RepairJobCommand command, RepairWorkspace workspace) {
            throw new UnsupportedOperationException("Host verifier never publishes");
        }
    }
}
