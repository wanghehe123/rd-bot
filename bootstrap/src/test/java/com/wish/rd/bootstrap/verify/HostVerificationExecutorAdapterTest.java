package com.wish.rd.bootstrap.verify;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.requirement.model.AgentWorkflowPlan;
import com.wish.rd.engine.requirement.verify.model.HostVerificationArtifact;
import com.wish.rd.engine.requirement.verify.model.HostVerificationRun;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStatus;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStep;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStepName;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStepStatus;
import com.wish.rd.engine.requirement.verify.impl.InMemoryHostVerificationStore;
import com.wish.rd.exec.repair.verify.HostVerificationCommandDetector;
import com.wish.rd.exec.repair.verify.model.HostVerificationCommandResult;
import com.wish.rd.rag.qa.QaValidationProfileService;
import com.wish.rd.rag.qa.impl.InMemoryQaValidationProfileStore;
import com.wish.rd.rag.qa.model.QaValidationProfileCommand;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostVerificationExecutorAdapterTest {

    @TempDir
    Path workspace;

    @TempDir
    Path evidenceRoot;

    private InMemoryHostVerificationStore store;
    private FakeCommandRunner runner;
    private AtomicLong ids;
    private AtomicLong clock;

    @BeforeEach
    void setUp() {
        store = new InMemoryHostVerificationStore();
        runner = new FakeCommandRunner();
        ids = new AtomicLong(8000);
        clock = new AtomicLong(1_700_000_000_000L);
    }

    @Test
    void docsOnlyChangedFilesSkipBuildAndStaticWithoutRunningCommands() throws Exception {
        writeVitePackageJson();
        HostVerificationExecutorAdapter adapter = adapter(List.of("README.md"));

        HostVerificationRun run = adapter.verify(task(), codingStage(), AgentWorkflowPlan.production(), 1);

        assertEquals(HostVerificationStatus.SKIPPED_DOCS_ONLY, run.status());
        assertTrue(run.docsOnly());
        assertEquals(1, run.remediationCount());
        assertEquals(1, run.attemptNo());
        assertEquals("", run.parentRunId());
        assertEquals(HostVerificationStepStatus.SKIPPED, step(run, HostVerificationStepName.BUILD).status());
        assertEquals(HostVerificationStepStatus.SKIPPED, step(run, HostVerificationStepName.STATIC).status());
        assertTrue(runner.commands.isEmpty(), runner.commands.toString());
        List<HostVerificationArtifact> skipped = store.listArtifacts(run.runId());
        assertTrue(skipped.stream().anyMatch(artifact -> "VERIFY_BUILD_LOG".equals(artifact.artifactType())),
                skipped.toString());
        assertTrue(skipped.stream().anyMatch(artifact -> "VERIFY_STATIC_LOG".equals(artifact.artifactType())),
                skipped.toString());
    }

    @Test
    void nestedBuildWithoutTypecheckStillPersistsStaticEvidence() throws Exception {
        Files.createDirectories(workspace.resolve("client"));
        Files.createDirectories(workspace.resolve("server"));
        Files.writeString(workspace.resolve("package.json"), """
                {
                  "scripts": {
                    "install:all": "npm --prefix server install && npm --prefix client install",
                    "dev": "concurrently npm:dev:*"
                  }
                }
                """);
        Files.writeString(workspace.resolve("client/package.json"), """
                { "scripts": { "dev": "vite", "build": "tsc && vite build" } }
                """);
        Files.writeString(workspace.resolve("server/package.json"), """
                { "scripts": { "dev": "tsx watch src/index.ts", "build": "tsc" } }
                """);
        Files.writeString(workspace.resolve("client/tsconfig.json"), "{}");
        Files.writeString(workspace.resolve("server/tsconfig.json"), "{}");
        QaValidationProfileService profiles = new QaValidationProfileService(new InMemoryQaValidationProfileStore());
        profiles.updateTask("9001", new QaValidationProfileCommand(
                "AUTO",
                "",
                "",
                "",
                List.of(),
                List.of(),
                List.of("npm --prefix server install", "npm --prefix server run build",
                        "npm --prefix client install", "npm --prefix client run build"),
                null
        ));
        HostVerificationExecutorAdapter adapter = adapter(List.of("client/src/pages/customer/Home.tsx"), profiles);

        HostVerificationRun run = adapter.verify(task(), codingStage(), AgentWorkflowPlan.production(), 0);

        assertEquals(HostVerificationStatus.SUCCEEDED, run.status());
        assertEquals(HostVerificationStepStatus.SUCCEEDED, step(run, HostVerificationStepName.BUILD).status());
        assertEquals(HostVerificationStepStatus.SKIPPED, step(run, HostVerificationStepName.STATIC).status());
        assertFalse(runner.commands.stream().anyMatch(command -> command.contains("tsc --noEmit")),
                runner.commands.toString());
        List<HostVerificationArtifact> artifacts = store.listArtifacts(run.runId());
        assertTrue(artifacts.stream().anyMatch(artifact -> "VERIFY_BUILD_LOG".equals(artifact.artifactType())),
                artifacts.toString());
        assertTrue(artifacts.stream().anyMatch(artifact -> "VERIFY_STATIC_LOG".equals(artifact.artifactType())),
                artifacts.toString());
    }

    @Test
    void prepareFailureRecordsEnvironmentRunInsteadOfThrowing() {
        HostVerificationExecutorAdapter adapter = new HostVerificationExecutorAdapter(
                store,
                new HostVerificationCommandDetector(),
                runner,
                (task, codingStage) -> {
                    throw new IllegalArgumentException("workBranch must not be blank");
                },
                (task, codingStage, prepared) -> List.of("src/App.tsx"),
                ids::incrementAndGet,
                clock::incrementAndGet,
                600,
                evidenceRoot
        );

        HostVerificationRun run = adapter.verify(task(), codingStage(), AgentWorkflowPlan.production(), 0);

        assertEquals(HostVerificationStatus.FAILED_NEEDS_HUMAN, run.status());
        assertEquals("ENVIRONMENT", run.failureCategory());
        assertTrue(run.errorMessage().contains("workBranch"), run.errorMessage());
        assertEquals(HostVerificationStepStatus.SKIPPED, step(run, HostVerificationStepName.BUILD).status());
        assertEquals(HostVerificationStepStatus.SKIPPED, step(run, HostVerificationStepName.STATIC).status());
        assertTrue(runner.commands.isEmpty(), runner.commands.toString());
    }

    @Test
    void buildNonZeroSkipsStaticAndClassifiesProductDefect() throws Exception {
        writeVitePackageJson();
        runner.buildExitCode = 1;
        HostVerificationExecutorAdapter adapter = adapter(List.of("src/App.tsx"));

        HostVerificationRun run = adapter.verify(task(), codingStage(), AgentWorkflowPlan.production(), 0);

        assertEquals(HostVerificationStatus.FAILED_RETRYABLE, run.status());
        assertEquals("PRODUCT_DEFECT", run.failureCategory());
        assertTrue(run.errorMessage().contains("exited 1"), run.errorMessage());
        assertTrue(run.errorMessage().contains("output for"), run.errorMessage());
        assertEquals(HostVerificationStepStatus.FAILED, step(run, HostVerificationStepName.BUILD).status());
        HostVerificationStep staticStep = step(run, HostVerificationStepName.STATIC);
        assertEquals(HostVerificationStepStatus.SKIPPED, staticStep.status());
        assertTrue(staticStep.errorMessage().toLowerCase().contains("build"), staticStep.errorMessage());
        assertFalse(runner.commands.stream().anyMatch(command -> command.contains("typecheck")), runner.commands.toString());
        assertFalse(runner.commands.isEmpty());
    }

    @Test
    void buildZeroThenStaticRunsAndSucceeds() throws Exception {
        writeVitePackageJson();
        HostVerificationExecutorAdapter adapter = adapter(List.of("src/App.tsx"));

        HostVerificationRun first = adapter.verify(task(), codingStage(), AgentWorkflowPlan.production(), 0);
        assertEquals(HostVerificationStatus.SUCCEEDED, first.status());
        assertEquals(HostVerificationStepStatus.SUCCEEDED, step(first, HostVerificationStepName.BUILD).status());
        assertEquals(HostVerificationStepStatus.SUCCEEDED, step(first, HostVerificationStepName.STATIC).status());
        assertTrue(runner.commands.stream().anyMatch(command -> command.contains("build") || command.contains("npm install")),
                runner.commands.toString());
        assertTrue(runner.commands.stream().anyMatch(command -> command.contains("typecheck")), runner.commands.toString());
        List<HostVerificationArtifact> artifacts = store.listArtifacts(first.runId());
        assertTrue(artifacts.stream().anyMatch(artifact -> artifact.relativePath().contains("verify-evidence/build/")));
        assertTrue(artifacts.stream().anyMatch(artifact -> artifact.relativePath().contains("verify-evidence/static/")));
        assertTrue(artifacts.stream().allMatch(artifact -> artifact.sha256() != null && !artifact.sha256().isBlank()));

        HostVerificationRun second = adapter.verify(task(), codingStage(), AgentWorkflowPlan.production(), 0);
        assertEquals(2, second.attemptNo());
        assertEquals(first.runId(), second.parentRunId());
    }

    @Test
    void timeoutIsInfrastructureAndNeedsHuman() throws Exception {
        writeVitePackageJson();
        runner.timedOut = true;
        HostVerificationExecutorAdapter adapter = adapter(List.of("src/App.tsx"));

        HostVerificationRun run = adapter.verify(task(), codingStage(), AgentWorkflowPlan.production(), 0);

        assertEquals(HostVerificationStatus.FAILED_NEEDS_HUMAN, run.status());
        assertEquals("QA_INFRASTRUCTURE", run.failureCategory());
        assertEquals(HostVerificationStepStatus.FAILED, step(run, HostVerificationStepName.BUILD).status());
        assertEquals(HostVerificationStepStatus.SKIPPED, step(run, HostVerificationStepName.STATIC).status());
        assertFalse(runner.commands.stream().anyMatch(command -> command.contains("typecheck")), runner.commands.toString());
    }

    @Test
    void ambiguousEmptyRepositoryNeedsHumanWithoutRunnerCalls() {
        HostVerificationExecutorAdapter adapter = adapter(List.of("src/App.tsx"));

        HostVerificationRun run = adapter.verify(task(), codingStage(), AgentWorkflowPlan.production(), 0);

        assertEquals(HostVerificationStatus.FAILED_NEEDS_HUMAN, run.status());
        assertEquals("REQUIREMENT_AMBIGUITY", run.failureCategory());
        assertTrue(runner.commands.isEmpty(), runner.commands.toString());
        assertEquals(HostVerificationStepStatus.SKIPPED, step(run, HostVerificationStepName.BUILD).status());
        assertEquals(HostVerificationStepStatus.SKIPPED, step(run, HostVerificationStepName.STATIC).status());
    }

    @Test
    void taskProfileBuildCommandsOverrideAutoDetectFromPackageJson() throws Exception {
        writePackageJsonThatAutoDetectsInstallTestAndBuild();
        QaValidationProfileService profiles = new QaValidationProfileService(new InMemoryQaValidationProfileStore());
        profiles.updateTask("9001", new QaValidationProfileCommand(
                "AUTO",
                "",
                "",
                "",
                List.of(),
                List.of(),
                List.of("npm run build"),
                null
        ));
        HostVerificationExecutorAdapter adapter = adapter(List.of("src/App.tsx"), profiles);

        HostVerificationRun run = adapter.verify(task(), codingStage(), AgentWorkflowPlan.production(), 0);

        assertEquals(HostVerificationStatus.SUCCEEDED, run.status());
        assertEquals(List.of("npm run build"), step(run, HostVerificationStepName.BUILD).commands());
        assertEquals(List.of("npm run build"), runner.commands.stream().filter(command -> !command.contains("typecheck")).toList());
        assertFalse(runner.commands.contains("npm ci"), runner.commands.toString());
        assertFalse(runner.commands.contains("npm install"), runner.commands.toString());
        assertFalse(runner.commands.contains("npm test"), runner.commands.toString());
    }

    @Test
    void projectProfileBuildCommandsUsedWhenTaskOverrideAbsent() throws Exception {
        writePackageJsonThatAutoDetectsInstallTestAndBuild();
        QaValidationProfileService profiles = new QaValidationProfileService(new InMemoryQaValidationProfileStore());
        profiles.updateProject("proj-1", new QaValidationProfileCommand(
                "AUTO",
                "",
                "",
                "",
                List.of(),
                List.of(),
                List.of("npm run build"),
                null
        ));
        HostVerificationExecutorAdapter adapter = adapter(List.of("src/App.tsx"), profiles);

        HostVerificationRun run = adapter.verify(task("proj-1"), codingStage(), AgentWorkflowPlan.production(), 0);

        assertEquals(HostVerificationStatus.SUCCEEDED, run.status());
        assertEquals(List.of("npm run build"), step(run, HostVerificationStepName.BUILD).commands());
        assertFalse(runner.commands.contains("npm test"), runner.commands.toString());
    }

    private HostVerificationExecutorAdapter adapter(List<String> changedFiles) {
        return adapter(changedFiles, null);
    }

    private HostVerificationExecutorAdapter adapter(List<String> changedFiles, QaValidationProfileService profiles) {
        return new HostVerificationExecutorAdapter(
                store,
                new HostVerificationCommandDetector(),
                runner,
                (task, codingStage) -> workspace,
                (task, codingStage, prepared) -> changedFiles,
                ids::incrementAndGet,
                clock::incrementAndGet,
                600,
                evidenceRoot,
                profiles
        );
    }

    private HostVerificationStep step(HostVerificationRun run, HostVerificationStepName name) {
        return store.listSteps(run.runId()).stream()
                .filter(step -> step.step() == name)
                .findFirst()
                .orElseThrow();
    }

    private static RdRequirementTask task() {
        return task("");
    }

    private static RdRequirementTask task(String projectId) {
        return RdRequirementTask.created(
                "9001",
                new CreateRequirementTaskCommand(
                        "title",
                        "P2",
                        "ADMIN",
                        "",
                        "",
                        projectId,
                        "",
                        "",
                        "https://example.com/repo.git",
                        "acme",
                        "repo",
                        "main",
                        "ok",
                        List.of(),
                        List.of(),
                        false
                ),
                1L
        );
    }

    private static AgentStageRun codingStage() {
        return AgentStageRun.pending("7001", "9001", AgentRole.CODING_AGENT, 1, "idem-1", 1L);
    }

    private void writeVitePackageJson() throws Exception {
        Files.writeString(workspace.resolve("package.json"), """
                {
                  "scripts": {
                    "dev": "vite",
                    "build": "vite build",
                    "typecheck": "tsc --noEmit"
                  }
                }
                """);
    }

    private void writePackageJsonThatAutoDetectsInstallTestAndBuild() throws Exception {
        Files.writeString(workspace.resolve("package.json"), """
                {
                  "scripts": {
                    "test": "vitest",
                    "build": "vite build",
                    "typecheck": "tsc --noEmit"
                  }
                }
                """);
        Files.writeString(workspace.resolve("package-lock.json"), "{}");
    }

    private static final class FakeCommandRunner implements HostVerificationExecutorAdapter.CommandExecutor {
        private final List<String> commands = new ArrayList<>();
        private int buildExitCode = 0;
        private boolean timedOut;

        @Override
        public HostVerificationCommandResult run(String command, Path workingDirectory, Duration timeout) {
            commands.add(command);
            if (timedOut) {
                return new HostVerificationCommandResult(-1, "command timed out", true);
            }
            int exitCode = command.contains("typecheck") ? 0 : buildExitCode;
            return new HostVerificationCommandResult(exitCode, "output for " + command, false);
        }
    }
}
