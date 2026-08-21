package com.wish.rd.bootstrap.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wish.rd.bootstrap.executor.impl.ProcessContainerRunner;
import com.wish.rd.exec.repair.docker.RepairWorkspaceFactory;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort;
import com.wish.rd.exec.repair.docker.model.ContainerNetworkPlan;
import com.wish.rd.exec.repair.docker.model.ContainerRunRequest;
import com.wish.rd.exec.repair.docker.model.ContainerRunResult;
import com.wish.rd.exec.repair.docker.model.ContainerSecurityPolicy;
import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.pi.AgentPrivateArtifactPublisher;
import com.wish.rd.exec.repair.pi.PiCredentialLeaseIssuer;
import com.wish.rd.exec.repair.pi.PiResourceManifestMaterializerPort;
import com.wish.rd.exec.repair.pi.PiSkillMaterializerPort;
import com.wish.rd.exec.repair.pi.impl.DockerPiAgentExecutor;
import com.wish.rd.exec.repair.pi.impl.InMemoryPiCredentialLeaseIssuer;
import com.wish.rd.exec.repair.result.StructuredResultValidator;
import com.wish.rd.exec.repair.runtime.AgentExecutionEventSink;
import com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest;
import com.wish.rd.exec.repair.security.model.ExecutionAllowlistPolicy;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Default-disabled acceptance probes that exercise the real Docker isolation
 * boundary used by Pi relay executions. Enable only on a Docker-capable host.
 */
@EnabledIfSystemProperty(
        named = "rd.integration.pi-docker-isolation.enabled",
        matches = "true"
)
class PiRealDockerIsolationAcceptanceTest {

    private static final String PI_IMAGE = "rd-bot/pi-agent:local";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ContainerSecurityPolicy PI_POLICY = new ContainerSecurityPolicy(
            true,
            true,
            true,
            true,
            "256m",
            "1",
            64,
            "1000:1000",
            Map.of(
                    "/work/pi-agent", "rw,exec,size=64m,uid=1000,gid=1000",
                    "/tmp", "rw,noexec,nosuid,size=64m,uid=1000,gid=1000",
                    "/home/node", "rw,noexec,nosuid,size=64m,uid=1000,gid=1000"
            )
    );
    private static final ContainerSecurityPolicy RELAY_POLICY = new ContainerSecurityPolicy(
            true,
            true,
            true,
            true,
            "256m",
            "1",
            64,
            "1000:1000",
            Map.of("/tmp", "rw,noexec,nosuid,size=32m,uid=1000,gid=1000")
    );

    @TempDir
    Path temporaryDirectory;

    private final Set<String> containerNames = new LinkedHashSet<>();
    private final Set<String> networkNames = new LinkedHashSet<>();
    private final Set<String> imageNames = new LinkedHashSet<>();
    private String probeImage;

    @Test
    void realDockerInternalNetworkBlocksDirectPublicEgress() throws Exception {
        String suffix = uniqueSuffix();
        ContainerNetworkPlan networkPlan = relayNetworkPlan(suffix);
        Path outputDirectory = writableDirectory("egress-output");
        String containerName = "rd-pi-isolation-egress-" + suffix;
        containerNames.add(containerName);

        ContainerRunRequest request = new ContainerRunRequest(
                containerName,
                probeImage(),
                List.of("node", "-e", """
                        const fs = require('node:fs');
                        (async () => {
                          let blocked = false;
                          let failure = '';
                          try {
                            await fetch('http://1.1.1.1/', { signal: AbortSignal.timeout(2500) });
                          } catch (error) {
                            blocked = true;
                            failure = String(error && error.name ? error.name : error);
                          }
                          const result = { blocked, failure };
                          fs.writeFileSync('/work/output/direct-egress.json', JSON.stringify(result));
                          console.log(JSON.stringify(result));
                          process.exit(blocked ? 0 : 73);
                        })().catch(error => {
                          console.error(error && error.stack ? error.stack : String(error));
                          process.exit(74);
                        });
                        """),
                Map.of(),
                Map.of(outputDirectory.toString(), "/work/output"),
                "/work",
                networkPlan.internalNetworkName(),
                false,
                false,
                outputDirectory,
                false,
                "",
                20_000L,
                PI_POLICY,
                networkPlan
        );

        ContainerRunResult result = runner().run(request);
        Path evidencePath = outputDirectory.resolve("direct-egress.json");
        assertTrue(Files.isRegularFile(evidencePath), () -> "Pi egress probe emitted no artifact; exit="
                + result.exitCode() + " stdout=" + result.stdout() + " stderr=" + result.stderr()
                + " dockerMeta=" + dockerMetadata(outputDirectory));
        JsonNode evidence = OBJECT_MAPPER.readTree(Files.readString(evidencePath, StandardCharsets.UTF_8));

        assertEquals(0, result.exitCode(), () -> "Pi reached public egress: " + result.stderr());
        assertTrue(evidence.path("blocked").asBoolean(), "Pi direct public egress was not blocked");
    }

    @Test
    void realDockerReviewerMountAndRootfsBlockWrites() throws Exception {
        String suffix = uniqueSuffix();
        Path repositoryDirectory = writableDirectory("reviewer-repository");
        Path outputDirectory = writableDirectory("reviewer-output");
        Path hostSentinel = temporaryDirectory.resolve("host-only-sentinel.txt").toAbsolutePath();
        Files.writeString(repositoryDirectory.resolve("baseline.txt"), "repository-original", StandardCharsets.UTF_8);
        Files.writeString(hostSentinel, "host-original", StandardCharsets.UTF_8);
        String containerName = "rd-pi-isolation-reviewer-" + suffix;
        containerNames.add(containerName);
        String hostPath = OBJECT_MAPPER.writeValueAsString(hostSentinel.toString());

        ContainerRunRequest request = new ContainerRunRequest(
                containerName,
                probeImage(),
                List.of("node", "-e", """
                        const fs = require('node:fs');
                        const hostPath = %s;
                        function writeSucceeds(path) {
                          try {
                            fs.writeFileSync(path, 'probe-write');
                            return true;
                          } catch (error) {
                            return false;
                          }
                        }
                        const evidence = {
                          repoWriteSucceeded: writeSucceeds('/work/repo/reviewer-blocked.txt'),
                          rootfsWriteSucceeded: writeSucceeds('/etc/rd-pi-isolation-probe'),
                          hostPathWriteSucceeded: writeSucceeds(hostPath),
                          outputWriteSucceeded: writeSucceeds('/work/output/reviewer-output-write.txt'),
                        };
                        fs.writeFileSync('/work/output/reviewer-write.json', JSON.stringify(evidence));
                        console.log(JSON.stringify(evidence));
                        process.exit(!evidence.repoWriteSucceeded
                          && !evidence.rootfsWriteSucceeded
                          && !evidence.hostPathWriteSucceeded
                          && evidence.outputWriteSucceeded ? 0 : 75);
                        """.formatted(hostPath)),
                Map.of(),
                Map.of(
                        repositoryDirectory.toString(), "/work/repo:ro",
                        outputDirectory.toString(), "/work/output"
                ),
                "/work/repo",
                "none",
                false,
                false,
                outputDirectory,
                false,
                "",
                20_000L,
                PI_POLICY
        );

        ContainerRunResult result = runner().run(request);
        Path evidencePath = outputDirectory.resolve("reviewer-write.json");
        assertTrue(Files.isRegularFile(evidencePath), () -> "reviewer probe emitted no artifact; exit="
                + result.exitCode() + " stdout=" + result.stdout() + " stderr=" + result.stderr());
        JsonNode evidence = OBJECT_MAPPER.readTree(Files.readString(evidencePath, StandardCharsets.UTF_8));

        assertEquals(0, result.exitCode(), () -> "reviewer isolation probe failed: " + result.stderr());
        assertTrue(!evidence.path("repoWriteSucceeded").asBoolean(), "reviewer wrote its read-only repository");
        assertTrue(!evidence.path("rootfsWriteSucceeded").asBoolean(), "reviewer wrote its read-only rootfs");
        assertTrue(!evidence.path("hostPathWriteSucceeded").asBoolean(), "reviewer wrote an unmounted host path");
        assertTrue(evidence.path("outputWriteSucceeded").asBoolean(), "reviewer lost its allowed output mount");
        assertEquals("repository-original", Files.readString(repositoryDirectory.resolve("baseline.txt"), StandardCharsets.UTF_8));
        assertEquals("host-original", Files.readString(hostSentinel, StandardCharsets.UTF_8));
    }

    @Test
    void realDockerPidsLimitStopsBoundedForkProbe() throws Exception {
        String suffix = uniqueSuffix();
        Path outputDirectory = writableDirectory("fork-output");
        String containerName = "rd-pi-isolation-fork-" + suffix;
        containerNames.add(containerName);
        ContainerSecurityPolicy forkPolicy = new ContainerSecurityPolicy(
                true,
                true,
                true,
                true,
                "256m",
                "1",
                64,
                "1000:1000",
                PI_POLICY.tmpfsMounts()
        );

        ContainerRunRequest request = new ContainerRunRequest(
                containerName,
                probeImage(),
                List.of("node", "-e", """
                        const { spawn } = require('node:child_process');
                        const fs = require('node:fs');
                        const children = [];
                        const attempted = 160;
                        let failures = 0;
                        let started = 0;
                        for (let index = 0; index < attempted; index += 1) {
                          try {
                            const child = spawn('sleep', ['10']);
                            children.push(child);
                            child.once('spawn', () => { started += 1; });
                            child.once('error', () => { failures += 1; });
                          } catch (error) {
                            failures += 1;
                          }
                        }
                        setTimeout(() => {
                          for (const child of children) {
                            if (child && child.pid) child.kill('SIGKILL');
                          }
                          setTimeout(() => {
                            const evidence = { attempted, started, failures };
                            fs.writeFileSync('/work/output/fork-limit.json', JSON.stringify(evidence));
                            console.log(JSON.stringify(evidence));
                            process.exit(failures > 0 && started < attempted ? 0 : 76);
                          }, 300);
                        }, 1000);
                        """),
                Map.of(),
                Map.of(outputDirectory.toString(), "/work/output"),
                "/work",
                "none",
                false,
                false,
                outputDirectory,
                false,
                "",
                20_000L,
                forkPolicy
        );

        ContainerRunResult result = runner().run(request);
        Path evidencePath = outputDirectory.resolve("fork-limit.json");
        assertTrue(Files.isRegularFile(evidencePath), () -> "fork probe emitted no artifact; exit="
                + result.exitCode() + " stdout=" + result.stdout() + " stderr=" + result.stderr());
        JsonNode evidence = OBJECT_MAPPER.readTree(Files.readString(evidencePath, StandardCharsets.UTF_8));

        assertEquals(0, result.exitCode(), () -> "PID limit did not contain the bounded fork probe: " + result.stderr());
        assertTrue(evidence.path("failures").asInt() > 0, "fork probe never hit the Docker PID limit");
        assertTrue(evidence.path("started").asInt() < evidence.path("attempted").asInt(),
                "all fork attempts escaped the Docker PID limit");
    }

    @Test
    void realDockerMemoryLimitProducesAnOomTermination() throws Exception {
        String suffix = uniqueSuffix();
        Path outputDirectory = writableDirectory("oom-output");
        String containerName = "rd-pi-isolation-oom-" + suffix;
        containerNames.add(containerName);
        ContainerSecurityPolicy memoryPolicy = new ContainerSecurityPolicy(
                true,
                true,
                true,
                true,
                "96m",
                "1",
                64,
                "1000:1000",
                PI_POLICY.tmpfsMounts()
        );

        ContainerRunRequest request = new ContainerRunRequest(
                containerName,
                probeImage(),
                List.of("node", "-e", """
                        const fs = require('node:fs');
                        console.log('allocating beyond the Docker memory limit');
                        const allocation = Buffer.alloc(384 * 1024 * 1024, 1);
                        fs.writeFileSync('/work/output/unexpected-memory-success.json', JSON.stringify({ bytes: allocation.length }));
                        process.exit(77);
                        """),
                Map.of(),
                Map.of(outputDirectory.toString(), "/work/output"),
                "/work",
                "none",
                false,
                false,
                outputDirectory,
                false,
                "",
                20_000L,
                memoryPolicy
        );

        ContainerRunResult result = runner().run(request);
        JsonNode inspect = dockerInspect(containerName);
        boolean oomKilled = inspect.get(0).path("State").path("OOMKilled").asBoolean();
        int observedExitCode = inspect.get(0).path("State").path("ExitCode").asInt();

        assertTrue(result.exitCode() != 0, "memory probe unexpectedly completed");
        assertTrue(oomKilled || observedExitCode == 137 || result.exitCode() == 137,
                () -> "memory limit did not cause an OOM-style termination; docker exit=" + result.exitCode()
                        + " inspect exit=" + observedExitCode + " stderr=" + result.stderr());
        assertTrue(!Files.exists(outputDirectory.resolve("unexpected-memory-success.json")),
                "memory probe allocated past the configured hard limit");
    }

    @Test
    void realDockerTimeoutCleansPiContainerRelaySidecarAndTaskNetwork() throws Exception {
        String suffix = uniqueSuffix();
        ContainerNetworkPlan networkPlan = relayNetworkPlan(suffix);
        Path outputDirectory = writableDirectory("timeout-output");
        String containerName = "rd-pi-isolation-timeout-" + suffix;
        containerNames.add(containerName);

        ContainerRunRequest request = new ContainerRunRequest(
                containerName,
                probeImage(),
                List.of("node", "-e", "setInterval(() => {}, 1000);"),
                Map.of(),
                Map.of(outputDirectory.toString(), "/work/output"),
                "/work",
                networkPlan.internalNetworkName(),
                false,
                false,
                outputDirectory,
                false,
                "",
                1_200L,
                PI_POLICY,
                networkPlan
        );

        ContainerRunResult result = runner().run(request);

        assertEquals(124, result.exitCode(), () -> "Pi timeout did not terminate the Docker run: " + result.stderr());
        assertTrue(waitForDockerAbsence(List.of("inspect", containerName)), "timed out Pi container still exists");
        assertTrue(waitForDockerAbsence(List.of("inspect", networkPlan.sidecar().containerName())),
                "timed out relay sidecar still exists");
        assertTrue(waitForDockerAbsence(List.of("network", "inspect", networkPlan.internalNetworkName())),
                "timed out task-local internal network still exists");
        assertTrue(dockerMetadata(outputDirectory).contains("\"timedOut\":true"),
                "runner metadata did not record the real Docker timeout");
    }

    @Test
    void realDockerRelayForwardsOnlyTheBoundLeaseWithoutLeakingRawProviderSecret() throws Exception {
        String rawSecret = "raw-provider-secret-" + uniqueSuffix();
        String taskId = "relay-task-" + uniqueSuffix();
        String stageRunId = "relay-stage-" + uniqueSuffix();
        String providerId = "relay-provider";
        try (RelayFixture fixture = new RelayFixture(rawSecret)) {
            PiCredentialLeaseIssuer.PiCredentialLease validLease = fixture.issuer.issue(
                    taskId,
                    stageRunId,
                    providerId,
                    rawSecret,
                    relayPolicy(),
                    Duration.ofMinutes(5),
                    2
            );
            RelayProbe valid = runRelayProbe(
                    "valid-" + uniqueSuffix(),
                    fixture,
                    taskId,
                    stageRunId,
                    providerId,
                    validLease.token(),
                    200,
                    "valid-relay.json"
            );
            PiCredentialLeaseIssuer.PiCredentialLease wrongLease = fixture.issuer.issue(
                    "other-" + taskId,
                    "other-" + stageRunId,
                    providerId,
                    rawSecret,
                    relayPolicy(),
                    Duration.ofMinutes(5),
                    1
            );
            RelayProbe denied = runRelayProbe(
                    "denied-" + uniqueSuffix(),
                    fixture,
                    taskId,
                    stageRunId,
                    providerId,
                    wrongLease.token(),
                    401,
                    "denied-relay.json"
            );

            assertEquals(0, valid.result().exitCode(), () -> "valid relay probe failed: " + valid.result().stderr()
                    + " evidence=" + valid.evidence());
            assertEquals(200, valid.evidence().path("status").asInt());
            assertTrue(valid.evidence().path("body").asText().contains("relay-ok"));
            assertTrue(valid.evidence().path("body").asText().contains("<redacted>"));
            assertFalse(valid.evidence().path("body").asText().contains(rawSecret));
            assertEquals(0, denied.result().exitCode(), () -> "wrong-binding relay probe failed: "
                    + denied.result().stderr());
            assertEquals(401, denied.evidence().path("status").asInt());
            assertEquals("", denied.evidence().path("body").asText());
            assertNotNull(fixture.upstreamRequest.get(), "valid lease never reached the controlled provider");
            assertEquals("Bearer " + rawSecret, fixture.upstreamRequest.get().headers().get("Authorization"));
            assertTrue(fixture.auditEvents.stream().anyMatch(event -> "FORWARDED".equals(event.outcome())));
            assertTrue(fixture.auditEvents.stream().anyMatch(event -> "REJECTED".equals(event.outcome())));

            assertRawSecretAbsent(rawSecret, "Pi environment artifact", valid.evidence().path("environment").toString());
            assertRawSecretAbsent(rawSecret, "Docker inspect", dockerInspect(valid.containerName()).toString());
            assertRawSecretAbsent(rawSecret, "Docker logs", dockerOutput(List.of("logs", valid.containerName())));
            assertRawSecretAbsent(rawSecret, "Pi output artifacts", directoryText(valid.outputDirectory()));
            assertRawSecretAbsent(rawSecret, "runner metadata", valid.result().metadata().toString());
            assertRawSecretAbsent(rawSecret, "docker metadata", dockerMetadata(valid.outputDirectory()));
        }
    }

    @Test
    void realDockerPiExecutorUsesOnlyOpaqueLeaseAndRejectsWrongTaskBinding() throws Exception {
        String rawSecret = "executor-provider-secret-" + uniqueSuffix();
        String taskId = "executor-task-" + uniqueSuffix();
        String stageRunId = "executor-stage-" + uniqueSuffix();
        try (RelayFixture fixture = new RelayFixture(rawSecret)) {
            ExecutorRelayProbe valid = runExecutorRelayProbe(
                    "executor-valid-" + uniqueSuffix(),
                    fixture,
                    fixture.issuer,
                    rawSecret,
                    taskId,
                    stageRunId,
                    200,
                    "executor-valid.json"
            );
            ExecutorRelayProbe denied = runExecutorRelayProbe(
                    "executor-denied-" + uniqueSuffix(),
                    fixture,
                    wrongBindingIssuer(fixture.issuer),
                    rawSecret,
                    "other-" + taskId,
                    "other-" + stageRunId,
                    401,
                    "executor-denied.json"
            );

            assertEquals(0, valid.exitCode(), () -> "executor valid relay probe failed: "
                    + valid.dockerMetadata() + " evidence=" + valid.evidence());
            assertEquals(200, valid.evidence().path("status").asInt());
            assertTrue(valid.evidence().path("body").asText().contains("relay-ok"));
            assertFalse(valid.evidence().path("body").asText().contains(rawSecret));
            assertEquals(0, denied.exitCode(), () -> "executor wrong-binding relay probe failed: "
                    + denied.dockerMetadata() + " evidence=" + denied.evidence());
            assertEquals(401, denied.evidence().path("status").asInt());
            assertNotNull(fixture.upstreamRequest.get());
            assertEquals("Bearer " + rawSecret, fixture.upstreamRequest.get().headers().get("Authorization"));
            assertTrue(fixture.auditEvents.stream().anyMatch(event -> "FORWARDED".equals(event.outcome())));
            assertTrue(fixture.auditEvents.stream().anyMatch(event -> "REJECTED".equals(event.outcome())));

            assertRawSecretAbsent(rawSecret, "executor Pi environment", valid.evidence().path("environment").toString());
            assertRawSecretAbsent(rawSecret, "executor Docker inspect", dockerInspect(valid.containerName()).toString());
            assertRawSecretAbsent(rawSecret, "executor Docker logs", dockerOutput(List.of("logs", valid.containerName())));
            assertRawSecretAbsent(rawSecret, "executor workspace", directoryText(valid.workspaceRoot()));
            assertRawSecretAbsent(rawSecret, "executor metadata", valid.executionResult().dockerMetadataJson().toString());
        }
    }

    @Test
    void realDockerPiExecutorMountsReviewerRepositoryReadOnlyAndBlocksHostWrites() throws Exception {
        String rawSecret = "reviewer-provider-secret-" + uniqueSuffix();
        String taskId = "reviewer-task-" + uniqueSuffix();
        String stageRunId = "reviewer-stage-" + uniqueSuffix();
        Path hostSentinel = temporaryDirectory.resolve("reviewer-host-sentinel.txt").toAbsolutePath();
        Files.writeString(hostSentinel, "host-original", StandardCharsets.UTF_8);
        try (RelayFixture fixture = new RelayFixture(rawSecret)) {
            ExecutorMountProbe probe = runExecutorReviewerMountProbe(
                    "reviewer-" + uniqueSuffix(),
                    fixture,
                    rawSecret,
                    taskId,
                    stageRunId,
                    hostSentinel
            );
            JsonNode inspect = dockerInspect(probe.containerName());
            boolean repoReadOnly = false;
            for (JsonNode mount : inspect.get(0).path("Mounts")) {
                if ("/work/repo".equals(mount.path("Destination").asText())) {
                    repoReadOnly = !mount.path("RW").asBoolean(true);
                }
            }

            assertEquals(0, probe.exitCode(), () -> "executor reviewer mount probe failed: "
                    + probe.dockerMetadata() + " evidence=" + probe.evidence());
            assertFalse(probe.evidence().path("repoWriteSucceeded").asBoolean());
            assertFalse(probe.evidence().path("rootfsWriteSucceeded").asBoolean());
            assertFalse(probe.evidence().path("hostPathWriteSucceeded").asBoolean());
            assertTrue(probe.evidence().path("outputWriteSucceeded").asBoolean());
            assertEquals(200, probe.evidence().path("relayStatus").asInt());
            assertTrue(repoReadOnly, "Docker inspect did not retain a read-only reviewer repository mount");
            assertNotNull(fixture.upstreamRequest.get(), "reviewer opaque lease never reached the controlled provider");
            assertEquals("Bearer " + rawSecret, fixture.upstreamRequest.get().headers().get("Authorization"));
            assertRawSecretAbsent(rawSecret, "reviewer Pi environment",
                    probe.evidence().path("environment").toString());
            assertRawSecretAbsent(rawSecret, "reviewer Docker inspect", inspect.toString());
            assertEquals("host-original", Files.readString(hostSentinel, StandardCharsets.UTF_8));
        }
    }

    @AfterEach
    void cleanupRealDockerResources() {
        for (String containerName : containerNames) {
            runDockerControl("rm", "-f", containerName);
        }
        for (String networkName : networkNames) {
            runDockerControl("network", "rm", networkName);
        }
        for (String imageName : imageNames) {
            runDockerControl("image", "rm", "-f", imageName);
        }
    }

    private ProcessContainerRunner runner() {
        DockerExecutorProperties properties = new DockerExecutorProperties();
        properties.setWorkspaceRoot(temporaryDirectory.resolve("runner-workspaces"));
        return new ProcessContainerRunner(properties);
    }

    private ContainerNetworkPlan relayNetworkPlan(String suffix) {
        return relayNetworkPlan(
                suffix,
                "http://host.docker.internal:1/internal/pi/credential-relay/proxy",
                "isolation-task-" + suffix,
                "isolation-stage-" + suffix,
                "isolation-provider"
        );
    }

    private ContainerNetworkPlan relayNetworkPlan(
            String suffix,
            String hostRelayUrl,
            String taskId,
            String stageRunId,
            String providerId
    ) {
        String networkName = "rd-pi-isolation-net-" + suffix;
        String sidecarName = "rd-pi-isolation-relay-" + suffix;
        containerNames.add(sidecarName);
        networkNames.add(networkName);
        return new ContainerNetworkPlan(
                networkName,
                new ContainerNetworkPlan.Sidecar(
                        sidecarName,
                        "rd-pi-relay",
                        PI_IMAGE,
                        "node",
                        List.of("/opt/rd-pi-bridge/src/rd-pi-relay-sidecar.mjs"),
                        Map.of(
                                "RD_PI_RELAY_HOST_URL", hostRelayUrl,
                                "RD_PI_RELAY_TASK_ID", taskId,
                                "RD_PI_RELAY_STAGE_RUN_ID", stageRunId,
                                "RD_PI_RELAY_PROVIDER_ID", providerId
                        ),
                        "bridge",
                        "http://127.0.0.1:8787/healthz",
                        10_000L,
                        RELAY_POLICY
                )
        );
    }

    private RelayProbe runRelayProbe(
            String suffix,
            RelayFixture fixture,
            String taskId,
            String stageRunId,
            String providerId,
            String leaseToken,
            int expectedStatus,
            String artifactName
    ) throws Exception {
        ContainerNetworkPlan networkPlan = relayNetworkPlan(
                suffix,
                fixture.hostRelayUrl(),
                taskId,
                stageRunId,
                providerId
        );
        Path outputDirectory = writableDirectory("relay-output-" + suffix);
        String containerName = "rd-pi-isolation-relay-probe-" + suffix;
        containerNames.add(containerName);
        ContainerRunRequest request = new ContainerRunRequest(
                containerName,
                probeImage(),
                List.of("node", "-e", """
                        const fs = require('node:fs');
                        (async () => {
                          const response = await fetch('http://rd-pi-relay:8787/chat/completions', {
                            method: 'POST',
                            headers: {
                              authorization: 'Bearer ' + process.env.RD_PI_CREDENTIAL_LEASE,
                              'content-type': 'application/json',
                            },
                            body: JSON.stringify({ probe: 'lease-binding' }),
                          });
                          const body = await response.text();
                          const evidence = { status: response.status, body, environment: process.env };
                          fs.writeFileSync('/work/output/%s', JSON.stringify(evidence));
                          process.exit(response.status === %d ? 0 : 81);
                        })().catch(error => {
                          console.error(error && error.stack ? error.stack : String(error));
                          process.exit(82);
                        });
                        """.formatted(artifactName, expectedStatus)),
                Map.of("RD_PI_CREDENTIAL_LEASE", leaseToken),
                Map.of(outputDirectory.toString(), "/work/output"),
                "/work",
                networkPlan.internalNetworkName(),
                false,
                false,
                outputDirectory,
                false,
                "",
                20_000L,
                PI_POLICY,
                networkPlan
        );
        ContainerRunResult result = runner().run(request);
        Path artifact = outputDirectory.resolve(artifactName);
        assertTrue(Files.isRegularFile(artifact), () -> "relay probe emitted no artifact; exit="
                + result.exitCode() + " stdout=" + result.stdout() + " stderr=" + result.stderr());
        JsonNode evidence = OBJECT_MAPPER.readTree(Files.readString(artifact, StandardCharsets.UTF_8));
        return new RelayProbe(result, evidence, outputDirectory, containerName);
    }

    private static PiCredentialLeaseIssuer.RelayPolicy relayPolicy() {
        return new PiCredentialLeaseIssuer.RelayPolicy(
                "http://controlled-provider.invalid/v1",
                List.of("POST"),
                List.of("/chat/completions"),
                true,
                4096,
                4096,
                Duration.ofSeconds(5)
        );
    }

    private ExecutorRelayProbe runExecutorRelayProbe(
            String suffix,
            RelayFixture fixture,
            PiCredentialLeaseIssuer leaseIssuer,
            String rawSecret,
            String taskId,
            String stageRunId,
            int expectedStatus,
            String artifactName
    ) throws Exception {
        Path workspaceRoot = writableDirectory("executor-workspace-" + suffix);
        String containerName = "rd-bot-pi-" + taskId;
        containerNames.add(containerName);
        DockerPiAgentExecutor executor = new DockerPiAgentExecutor(
                new RepairWorkspaceFactory(workspaceRoot, "{\"type\":\"object\"}"),
                runner(),
                new StructuredResultValidator(),
                new DockerPiAgentExecutor.Configuration(
                        probeImage(),
                        "",
                        List.of("node", "-e", """
                                const fs = require('node:fs');
                                (async () => {
                                  const response = await fetch('http://rd-pi-relay:8787/chat/completions', {
                                    method: 'POST',
                                    headers: {
                                      authorization: 'Bearer ' + process.env.RD_PI_CREDENTIAL_LEASE,
                                      'content-type': 'application/json',
                                    },
                                    body: JSON.stringify({ probe: 'executor-lease-binding' }),
                                  });
                                  const body = await response.text();
                                  const evidence = { status: response.status, body, environment: process.env };
                                  fs.writeFileSync('/work/output/%s', JSON.stringify(evidence));
                                  process.exit(response.status === %d ? 0 : 83);
                                })().catch(error => {
                                  console.error(error && error.stack ? error.stack : String(error));
                                  process.exit(84);
                                });
                                """.formatted(artifactName, expectedStatus)),
                        "bridge",
                        false,
                        false,
                        20_000L,
                        900_000L,
                        16L * 1024L * 1024L,
                        "v1",
                        true,
                        fixture.hostRelayUrl(),
                        DockerPiAgentExecutor.DEFAULT_CONTAINER_MEMORY_LIMIT
                ),
                RepairWorkspaceRepositoryPort.noop(),
                ExecutionAllowlistPolicy.disabled(),
                PiResourceManifestMaterializerPort.emptyOnly(),
                PiSkillMaterializerPort.emptyOnly(),
                AgentExecutionEventSink.noop(),
                AgentPrivateArtifactPublisher.noop(),
                ignored -> rawSecret,
                leaseIssuer
        );
        RepairExecutionResult executionResult = executor.execute(new AgentRuntimeExecutionRequest(
                piSnapshot(taskId, stageRunId),
                piCommand(taskId, stageRunId)
        ));
        Path outputDirectory = workspaceRoot.resolve(taskId).resolve("output");
        Path artifact = outputDirectory.resolve(artifactName);
        assertTrue(Files.isRegularFile(artifact), () -> "executor relay probe emitted no artifact; result="
                + executionResult.errorMessage() + " metadata=" + executionResult.dockerMetadataJson());
        JsonNode evidence = OBJECT_MAPPER.readTree(Files.readString(artifact, StandardCharsets.UTF_8));
        JsonNode dockerMetadata = OBJECT_MAPPER.readTree(Files.readString(
                outputDirectory.resolve("docker-meta.json"), StandardCharsets.UTF_8));
        return new ExecutorRelayProbe(
                executionResult,
                evidence,
                workspaceRoot,
                containerName,
                dockerMetadata.path("exitCode").asInt(-1),
                dockerMetadata.toString()
        );
    }

    private ExecutorMountProbe runExecutorReviewerMountProbe(
            String suffix,
            RelayFixture fixture,
            String rawSecret,
            String taskId,
            String stageRunId,
            Path hostSentinel
    ) throws Exception {
        Path workspaceRoot = writableDirectory("executor-reviewer-workspace-" + suffix);
        String containerName = "rd-bot-pi-" + taskId;
        containerNames.add(containerName);
        String hostPath = OBJECT_MAPPER.writeValueAsString(hostSentinel.toString());
        DockerPiAgentExecutor executor = new DockerPiAgentExecutor(
                new RepairWorkspaceFactory(workspaceRoot, "{\"type\":\"object\"}"),
                runner(),
                new StructuredResultValidator(),
                new DockerPiAgentExecutor.Configuration(
                        probeImage(),
                        "",
                        List.of("node", "-e", """
                                const fs = require('node:fs');
                                const hostPath = %s;
                                function writeSucceeds(path) {
                                  try {
                                    fs.writeFileSync(path, 'probe-write');
                                    return true;
                                  } catch (error) {
                                    return false;
                                  }
                                }
                                (async () => {
                                  const response = await fetch('http://rd-pi-relay:8787/chat/completions', {
                                    method: 'POST',
                                    headers: {
                                      authorization: 'Bearer ' + process.env.RD_PI_CREDENTIAL_LEASE,
                                      'content-type': 'application/json',
                                    },
                                    body: JSON.stringify({ probe: 'reviewer-mount' }),
                                  });
                                  await response.arrayBuffer();
                                  const evidence = {
                                    relayStatus: response.status,
                                    environment: process.env,
                                    repoWriteSucceeded: writeSucceeds('/work/repo/reviewer-blocked.txt'),
                                    rootfsWriteSucceeded: writeSucceeds('/etc/rd-pi-reviewer-probe'),
                                    hostPathWriteSucceeded: writeSucceeds(hostPath),
                                    outputWriteSucceeded: writeSucceeds('/work/output/reviewer-output-write.txt'),
                                  };
                                  fs.writeFileSync('/work/output/reviewer-mount.json', JSON.stringify(evidence));
                                  process.exit(response.status === 200
                                    && !evidence.repoWriteSucceeded
                                    && !evidence.rootfsWriteSucceeded
                                    && !evidence.hostPathWriteSucceeded
                                    && evidence.outputWriteSucceeded ? 0 : 85);
                                })().catch(error => {
                                  console.error(error && error.stack ? error.stack : String(error));
                                  process.exit(86);
                                });
                                """.formatted(hostPath)),
                        "bridge",
                        false,
                        false,
                        20_000L,
                        900_000L,
                        16L * 1024L * 1024L,
                        "v1",
                        true,
                        fixture.hostRelayUrl(),
                        DockerPiAgentExecutor.DEFAULT_CONTAINER_MEMORY_LIMIT
                ),
                RepairWorkspaceRepositoryPort.noop(),
                ExecutionAllowlistPolicy.disabled(),
                PiResourceManifestMaterializerPort.emptyOnly(),
                PiSkillMaterializerPort.emptyOnly(),
                AgentExecutionEventSink.noop(),
                AgentPrivateArtifactPublisher.noop(),
                ignored -> rawSecret,
                fixture.issuer
        );
        RepairExecutionResult executionResult = executor.execute(new AgentRuntimeExecutionRequest(
                piSnapshot(taskId, stageRunId, "REQUIREMENT_REVIEWER"),
                piCommand(taskId, stageRunId, "REQUIREMENT_REVIEWER")
        ));
        Path outputDirectory = workspaceRoot.resolve(taskId).resolve("output");
        Path artifact = outputDirectory.resolve("reviewer-mount.json");
        assertTrue(Files.isRegularFile(artifact), () -> "executor reviewer mount probe emitted no artifact; result="
                + executionResult.errorMessage() + " metadata=" + executionResult.dockerMetadataJson());
        JsonNode evidence = OBJECT_MAPPER.readTree(Files.readString(artifact, StandardCharsets.UTF_8));
        JsonNode dockerMetadata = OBJECT_MAPPER.readTree(Files.readString(
                outputDirectory.resolve("docker-meta.json"), StandardCharsets.UTF_8));
        return new ExecutorMountProbe(
                executionResult,
                evidence,
                containerName,
                dockerMetadata.path("exitCode").asInt(-1),
                dockerMetadata.toString()
        );
    }

    private static AgentExecutionProfileSnapshot piSnapshot(String taskId, String stageRunId) throws Exception {
        return piSnapshot(taskId, stageRunId, "CODING_AGENT");
    }

    private static AgentExecutionProfileSnapshot piSnapshot(String taskId, String stageRunId, String role) throws Exception {
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("snapshotVersion", 1);
        snapshot.put("stageRunId", stageRunId);
        snapshot.put("taskId", taskId);
        snapshot.put("role", role);
        snapshot.put("attemptNo", 1);
        snapshot.put("runtimeType", "PI");
        snapshot.put("profileId", "relay-isolation-profile");
        snapshot.put("profileVersion", 1);
        snapshot.put("providerProfileId", "relay-provider");
        snapshot.put("modelOverride", "");
        snapshot.put("providerProtocol", "OPENAI_CHAT_COMPLETIONS");
        snapshot.put("providerBaseUrl", "http://controlled-provider.invalid/v1");
        snapshot.put("providerModelId", "relay-model");
        snapshot.put("providerAuthHeader", true);
        snapshot.put("credentialEnvironmentVariable", "PI_ISOLATION_PROVIDER_KEY");
        snapshot.put("extensionSetId", "");
        snapshot.put("toolPolicyId", "isolation-policy");
        snapshot.put("toolPolicyVersion", 1);
        snapshot.put("toolPolicy", Map.of(
                "hostAllow", List.of("read", "bash", "edit", "write", "rd_submit_result"),
                "allow", List.of("read", "bash", "edit", "write", "rd_submit_result"),
                "deny", List.of(),
                "effectiveAllow", List.of("bash", "edit", "read", "rd_submit_result", "write"),
                "enabled", true
        ));
        String json = OBJECT_MAPPER.writeValueAsString(snapshot);
        return new AgentExecutionProfileSnapshot(
                "snapshot-" + taskId,
                stageRunId,
                taskId,
                role,
                1,
                AgentRuntimeType.PI,
                json,
                AgentExecutionProfileSnapshot.sha256(json),
                1L
        );
    }

    private static RepairJobCommand piCommand(String taskId, String stageRunId) {
        return piCommand(taskId, stageRunId, "CODING_AGENT");
    }

    private static RepairJobCommand piCommand(String taskId, String stageRunId, String role) {
        return new RepairJobCommand(
                "repair-" + taskId,
                taskId,
                "ticket-" + taskId,
                "Pi Docker isolation probe",
                "Run the controlled credential relay probe.",
                "https://github.com/example/isolation.git",
                "example",
                "isolation",
                "main",
                "isolation/" + taskId,
                Map.of("agentRole", role, "stageRunId", stageRunId),
                Map.of("repositoryPublishRequired", "false"),
                List.of()
        );
    }

    private static PiCredentialLeaseIssuer wrongBindingIssuer(InMemoryPiCredentialLeaseIssuer backing) {
        return new PiCredentialLeaseIssuer() {
            @Override
            public PiCredentialLease issue(
                    String taskId,
                    String stageRunId,
                    String providerId,
                    String rawCredential,
                    Duration ttl,
                    int maxCalls
            ) {
                return backing.issue("wrong-" + taskId, "wrong-" + stageRunId, providerId,
                        rawCredential, ttl, maxCalls);
            }

            @Override
            public PiCredentialLease issue(
                    String taskId,
                    String stageRunId,
                    String providerId,
                    String rawCredential,
                    RelayPolicy relayPolicy,
                    Duration ttl,
                    int maxCalls
            ) {
                return backing.issue("wrong-" + taskId, "wrong-" + stageRunId, providerId,
                        rawCredential, relayPolicy, ttl, maxCalls);
            }

            @Override
            public Optional<String> redeem(String token) {
                return backing.redeem(token);
            }

            @Override
            public Optional<String> redeem(String token, String taskId, String stageRunId, String providerId) {
                return backing.redeem(token, taskId, stageRunId, providerId);
            }

            @Override
            public Optional<RelayGrant> authorize(
                    String token,
                    String taskId,
                    String stageRunId,
                    String providerId,
                    String method,
                    String path,
                    int requestBytes
            ) {
                return backing.authorize(token, taskId, stageRunId, providerId, method, path, requestBytes);
            }
        };
    }

    private Path writableDirectory(String name) throws IOException {
        Path directory = Files.createDirectories(temporaryDirectory.resolve(name));
        try {
            Files.setPosixFilePermissions(directory, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE,
                    PosixFilePermission.OTHERS_EXECUTE
            ));
        } catch (UnsupportedOperationException ignored) {
            // Docker Desktop file sharing does not expose POSIX permissions on every host.
        }
        return directory;
    }

    private String probeImage() throws IOException {
        if (probeImage != null) {
            return probeImage;
        }
        String imageName = "rd-bot/pi-isolation-probe-" + uniqueSuffix() + ":local";
        Path buildContext = Files.createDirectories(temporaryDirectory.resolve("probe-image"));
        Files.writeString(buildContext.resolve("Dockerfile"), """
                FROM rd-bot/pi-agent:local
                ENTRYPOINT []
                """, StandardCharsets.UTF_8);
        DockerCommandResult result;
        try {
            result = runDocker(List.of("build", "--tag", imageName, buildContext.toString()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while building the Pi probe image", exception);
        }
        if (result.exitCode() != 0) {
            throw new IOException("failed to build Pi probe image: " + result.output());
        }
        imageNames.add(imageName);
        probeImage = imageName;
        return probeImage;
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static void runDockerControl(String... arguments) {
        try {
            runDocker(List.of(arguments));
        } catch (Exception ignored) {
            // Test failures remain authoritative; cleanup is best effort for a unique resource name.
        }
    }

    private static DockerCommandResult runDocker(List<String> arguments) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>();
        command.add("docker");
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(30L, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("docker command timed out: " + String.join(" ", command));
        }
        return new DockerCommandResult(process.exitValue(), new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static JsonNode dockerInspect(String resourceName) throws Exception {
        DockerCommandResult result = runDocker(List.of("inspect", resourceName));
        if (result.exitCode() != 0) {
            throw new IOException("docker inspect failed for " + resourceName + ": " + result.output());
        }
        return OBJECT_MAPPER.readTree(result.output());
    }

    private static boolean waitForDockerAbsence(List<String> inspectArguments) throws Exception {
        for (int attempt = 0; attempt < 50; attempt += 1) {
            DockerCommandResult result = runDocker(inspectArguments);
            if (result.exitCode() != 0) {
                return true;
            }
            Thread.sleep(100L);
        }
        return false;
    }

    private static String dockerMetadata(Path outputDirectory) {
        Path metadata = outputDirectory.resolve("docker-meta.json");
        try {
            return Files.isRegularFile(metadata) ? Files.readString(metadata, StandardCharsets.UTF_8) : "<missing>";
        } catch (IOException exception) {
            return "<unreadable: " + exception.getMessage() + ">";
        }
    }

    private static String dockerOutput(List<String> arguments) throws IOException {
        try {
            DockerCommandResult result = runDocker(arguments);
            if (result.exitCode() != 0) {
                throw new IOException("docker command failed: " + result.output());
            }
            return result.output();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while reading Docker evidence", exception);
        }
    }

    private static String directoryText(Path directory) throws IOException {
        List<Path> files;
        try (Stream<Path> paths = Files.walk(directory)) {
            files = paths.filter(Files::isRegularFile).toList();
        }
        StringBuilder text = new StringBuilder();
        for (Path file : files) {
            text.append(file.getFileName()).append('\n');
            text.append(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).append('\n');
        }
        return text.toString();
    }

    private static void assertRawSecretAbsent(String rawSecret, String location, String content) {
        assertFalse(content.contains(rawSecret), () -> "raw provider secret leaked into " + location);
    }

    private record RelayProbe(
            ContainerRunResult result,
            JsonNode evidence,
            Path outputDirectory,
            String containerName
    ) {
    }

    private record ExecutorRelayProbe(
            RepairExecutionResult executionResult,
            JsonNode evidence,
            Path workspaceRoot,
            String containerName,
            int exitCode,
            String dockerMetadata
    ) {
    }

    private record ExecutorMountProbe(
            RepairExecutionResult executionResult,
            JsonNode evidence,
            String containerName,
            int exitCode,
            String dockerMetadata
    ) {
    }

    private static final class RelayFixture implements AutoCloseable {

        private static final String RELAY_PATH = "/internal/pi/credential-relay/proxy";

        private final InMemoryPiCredentialLeaseIssuer issuer = new InMemoryPiCredentialLeaseIssuer();
        private final AtomicReference<PiCredentialRelayService.UpstreamRequest> upstreamRequest = new AtomicReference<>();
        private final List<PiCredentialRelayService.RelayAuditEvent> auditEvents = new CopyOnWriteArrayList<>();
        private final HttpServer server;

        private RelayFixture(String rawSecret) throws IOException {
            PiCredentialRelayService service = new PiCredentialRelayService(
                    issuer,
                    request -> {
                        upstreamRequest.set(request);
                        String body = "{\"result\":\"relay-ok\",\"echo\":\"" + rawSecret + "\"}";
                        return new PiCredentialRelayService.UpstreamResponse(
                                200,
                                Map.of("Content-Type", "application/json"),
                                body.getBytes(StandardCharsets.UTF_8)
                        );
                    },
                    auditEvents::add
            );
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
            server.createContext(RELAY_PATH, exchange -> proxy(service, exchange));
            server.start();
        }

        private String hostRelayUrl() {
            return "http://host.docker.internal:" + server.getAddress().getPort() + RELAY_PATH;
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static void proxy(PiCredentialRelayService service, HttpExchange exchange) throws IOException {
            Map<String, String> headers = new java.util.LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> {
                if (values != null && !values.isEmpty()) {
                    headers.put(name, values.getFirst());
                }
            });
            PiCredentialRelayService.ProxyResponse response = service.proxy(
                    bearer(exchange.getRequestHeaders().getFirst("Authorization")),
                    exchange.getRequestHeaders().getFirst("X-RD-Pi-Relay-Task-Id"),
                    exchange.getRequestHeaders().getFirst("X-RD-Pi-Relay-Stage-Run-Id"),
                    exchange.getRequestHeaders().getFirst("X-RD-Pi-Relay-Provider-Id"),
                    exchange.getRequestHeaders().getFirst("X-RD-Pi-Relay-Method"),
                    exchange.getRequestHeaders().getFirst("X-RD-Pi-Relay-Path"),
                    headers,
                    exchange.getRequestBody().readAllBytes()
            );
            response.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            exchange.sendResponseHeaders(response.status(), response.body().length);
            try (var responseBody = exchange.getResponseBody()) {
                responseBody.write(response.body());
            }
        }

        private static String bearer(String authorization) {
            String prefix = "Bearer ";
            if (authorization == null || !authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return "";
            }
            return authorization.substring(prefix.length()).strip();
        }
    }

    private record DockerCommandResult(int exitCode, String output) {
    }
}
