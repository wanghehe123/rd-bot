package com.wish.rd.bootstrap.verify;

import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.requirement.model.AgentWorkflowPlan;
import com.wish.rd.engine.requirement.verify.HostVerificationChangeSetResolver;
import com.wish.rd.engine.requirement.verify.HostVerificationPort;
import com.wish.rd.engine.requirement.verify.HostVerificationStore;
import com.wish.rd.engine.requirement.verify.HostVerificationWorkspaceFactory;
import com.wish.rd.engine.requirement.verify.model.HostVerificationArtifact;
import com.wish.rd.engine.requirement.verify.model.HostVerificationRun;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStatus;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStep;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStepName;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStepStatus;
import com.wish.rd.exec.repair.verify.HostVerificationCommandDetector;
import com.wish.rd.exec.repair.verify.model.HostVerificationCommandResult;
import com.wish.rd.exec.repair.verify.model.HostVerificationCommandSet;
import com.wish.rd.rag.qa.QaValidationProfileService;
import com.wish.rd.rag.qa.model.QaValidationProfile;
import com.wish.rd.rag.runtime.model.RdRequirementTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Host-side BUILD then STATIC executor for one {@link HostVerificationRun}.
 *
 * <p>Implements {@link HostVerificationPort} using a prepared workspace, the
 * command detector, and an allowlisted process runner. Coding {@code testStatus}
 * is ignored. Cheap remediations are recorded but not created here.
 */
public final class HostVerificationExecutorAdapter implements HostVerificationPort {

    /** Default wall-clock budget for the whole BUILD+STATIC sequence. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 600;

    private static final int MAX_ERROR_MESSAGE_CHARS = 4_000;
    private static final String BUILD_SKIPPED_AFTER_FAILURE = "not executed because BUILD failed";
    private static final String BUILD_SKIPPED_AMBIGUOUS = "not executed because BUILD commands could not be resolved";

    private final HostVerificationStore store;
    private final HostVerificationCommandDetector detector;
    private final CommandExecutor commandExecutor;
    private final HostVerificationWorkspaceFactory workspaceFactory;
    private final HostVerificationChangeSetResolver changeSetResolver;
    private final LongSupplier idGenerator;
    private final LongSupplier clock;
    private final int timeoutSeconds;
    private final Path evidenceRoot;
    private final QaValidationProfileService qaValidationProfileService;

    /**
     * Creates the host verification adapter.
     *
     * @param store              run/step/artifact persistence
     * @param detector           BUILD/STATIC command resolver
     * @param commandExecutor    process runner; tests inject a fake
     * @param workspaceFactory   prepared repository seam
     * @param changeSetResolver  candidate path list; tests inject README.md or src/App.tsx
     * @param idGenerator        run and artifact ids
     * @param clock              epoch millis
     * @param timeoutSeconds     wall-clock budget, default 600
     * @param evidenceRoot       directory that will contain {@code verify-evidence/}
     */
    public HostVerificationExecutorAdapter(
            HostVerificationStore store,
            HostVerificationCommandDetector detector,
            CommandExecutor commandExecutor,
            HostVerificationWorkspaceFactory workspaceFactory,
            HostVerificationChangeSetResolver changeSetResolver,
            LongSupplier idGenerator,
            LongSupplier clock,
            int timeoutSeconds,
            Path evidenceRoot
    ) {
        this(
                store,
                detector,
                commandExecutor,
                workspaceFactory,
                changeSetResolver,
                idGenerator,
                clock,
                timeoutSeconds,
                evidenceRoot,
                null
        );
    }

    /**
     * Creates the host verification adapter with an optional QA profile resolver.
     *
     * @param store                       run/step/artifact persistence
     * @param detector                    BUILD/STATIC command resolver
     * @param commandExecutor             process runner; tests inject a fake
     * @param workspaceFactory            prepared repository seam
     * @param changeSetResolver           candidate path list; tests inject README.md or src/App.tsx
     * @param idGenerator                 run and artifact ids
     * @param clock                       epoch millis
     * @param timeoutSeconds              wall-clock budget, default 600
     * @param evidenceRoot                directory that will contain {@code verify-evidence/}
     * @param qaValidationProfileService  task then project QA profile; {@code null} means auto-detect
     */
    public HostVerificationExecutorAdapter(
            HostVerificationStore store,
            HostVerificationCommandDetector detector,
            CommandExecutor commandExecutor,
            HostVerificationWorkspaceFactory workspaceFactory,
            HostVerificationChangeSetResolver changeSetResolver,
            LongSupplier idGenerator,
            LongSupplier clock,
            int timeoutSeconds,
            Path evidenceRoot,
            QaValidationProfileService qaValidationProfileService
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.detector = detector == null ? new HostVerificationCommandDetector() : detector;
        this.commandExecutor = Objects.requireNonNull(commandExecutor, "commandExecutor must not be null");
        this.workspaceFactory = Objects.requireNonNull(workspaceFactory, "workspaceFactory must not be null");
        this.changeSetResolver = Objects.requireNonNull(changeSetResolver, "changeSetResolver must not be null");
        this.idGenerator = idGenerator == null ? System::currentTimeMillis : idGenerator;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.timeoutSeconds = timeoutSeconds < 1 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        this.evidenceRoot = evidenceRoot;
        this.qaValidationProfileService = qaValidationProfileService;
    }

    /**
     * Runs one verification attempt against the prepared workspace.
     *
     * @param task                         requirement task
     * @param codingStage                  coding stage whose candidate is already applied
     * @param plan                         unused; remediations are Task 6
     * @param remediationCountAlreadyUsed  cheap remediations already consumed
     * @return persisted terminal snapshot
     */
    @Override
    public HostVerificationRun verify(
            RdRequirementTask task,
            AgentStageRun codingStage,
            AgentWorkflowPlan plan,
            int remediationCountAlreadyUsed
    ) {
        requireTaskAndStage(task, codingStage);
        if (remediationCountAlreadyUsed < 0) {
            throw new IllegalArgumentException("remediationCountAlreadyUsed must be >= 0");
        }
        // 工作区由调用方准备；这里只跑白名单命令，禁止清理 cache/ 或 node_modules
        Path workspace = workspaceFactory.prepare(task, codingStage);
        List<String> changedFiles = changeSetResolver.resolve(task, codingStage, workspace);
        // 任务覆盖 > 项目配置 > 仓库自动探测；Declared=false 的一侧仍走自动探测
        QaValidationProfile profile = resolveQaProfile(task);
        HostVerificationCommandSet commands = detector.detect(workspace, changedFiles, profile);
        HostVerificationRun run = store.create(newRun(task, codingStage, remediationCountAlreadyUsed, commands.docsOnly()));
        run = transition(run, HostVerificationStatus.CREATED, HostVerificationStatus.PREPARING, "", "");
        if (commands.docsOnly()) {
            return skipDocsOnly(run);
        }
        // BUILD 未声明且探测为空时不得空跑成功，否则陌生仓库会被当成通过
        if (!commands.buildCommandsDeclared() && commands.ambiguous()) {
            return failAmbiguous(run, commands);
        }
        return executeBuildThenStatic(run, workspace, commands);
    }

    private HostVerificationRun executeBuildThenStatic(
            HostVerificationRun run,
            Path workspace,
            HostVerificationCommandSet commands
    ) {
        run = transition(run, HostVerificationStatus.PREPARING, HostVerificationStatus.BUILDING, "", "");
        StepOutcome build = executeStep(
                run,
                HostVerificationStepName.BUILD,
                commands.buildCommands(),
                workspace
        );
        if (!build.successful()) {
            saveSkippedStep(run.runId(), HostVerificationStepName.STATIC, List.of(), BUILD_SKIPPED_AFTER_FAILURE);
            return failFromOutcome(run, HostVerificationStatus.BUILDING, build);
        }
        saveSucceededOrSkipped(run, HostVerificationStepName.BUILD, commands.buildCommands(), build);
        run = transition(run, HostVerificationStatus.BUILDING, HostVerificationStatus.STATIC_CHECKING, "", "");
        StepOutcome statik = executeStep(
                run,
                HostVerificationStepName.STATIC,
                commands.staticCommands(),
                workspace
        );
        if (!statik.successful()) {
            return failFromOutcome(run, HostVerificationStatus.STATIC_CHECKING, statik);
        }
        saveSucceededOrSkipped(run, HostVerificationStepName.STATIC, commands.staticCommands(), statik);
        return transition(run, HostVerificationStatus.STATIC_CHECKING, HostVerificationStatus.SUCCEEDED, "", "");
    }

    private HostVerificationRun skipDocsOnly(HostVerificationRun run) {
        saveSkippedStep(run.runId(), HostVerificationStepName.BUILD, List.of(), "docs-only change-set");
        saveSkippedStep(run.runId(), HostVerificationStepName.STATIC, List.of(), "docs-only change-set");
        return transition(
                run,
                HostVerificationStatus.PREPARING,
                HostVerificationStatus.SKIPPED_DOCS_ONLY,
                "",
                "candidate change is docs-only"
        );
    }

    private HostVerificationRun failAmbiguous(HostVerificationRun run, HostVerificationCommandSet commands) {
        String reason = commands.reason().isBlank()
                ? "cannot detect safe BUILD commands from the repository"
                : commands.reason();
        saveSkippedStep(run.runId(), HostVerificationStepName.BUILD, List.of(), reason);
        saveSkippedStep(run.runId(), HostVerificationStepName.STATIC, List.of(), BUILD_SKIPPED_AMBIGUOUS);
        return transition(
                run,
                HostVerificationStatus.PREPARING,
                HostVerificationStatus.FAILED_NEEDS_HUMAN,
                "REQUIREMENT_AMBIGUITY",
                reason
        );
    }

    private StepOutcome executeStep(
            HostVerificationRun run,
            HostVerificationStepName stepName,
            List<String> commands,
            Path workspace
    ) {
        if (commands == null || commands.isEmpty()) {
            return StepOutcome.skipped(stepName);
        }
        long started = now();
        long deadline = started + Duration.ofSeconds(timeoutSeconds).toMillis();
        StringBuilder log = new StringBuilder();
        List<String> executed = new ArrayList<>();
        Integer lastExit = null;
        for (String command : commands) {
            long remaining = deadline - now();
            if (remaining <= 0L) {
                log.append("\n[timed out before ").append(command).append(']');
                String artifactId = writeLog(run, stepName, log.toString());
                return StepOutcome.timeout(stepName, executed, log.toString(), now() - started, artifactId);
            }
            executed.add(command);
            log.append("$ ").append(command).append(System.lineSeparator());
            HostVerificationCommandResult result = commandExecutor.run(
                    command,
                    workspace,
                    Duration.ofMillis(Math.max(1L, remaining))
            );
            log.append(result.output());
            if (!result.output().endsWith("\n")) {
                log.append(System.lineSeparator());
            }
            lastExit = result.exitCode();
            if (result.timedOut()) {
                String artifactLog = log.toString();
                String artifactId = writeLog(run, stepName, artifactLog);
                return StepOutcome.timeout(stepName, executed, artifactLog, now() - started, artifactId);
            }
            if (result.exitCode() != 0) {
                String artifactLog = log.toString();
                String artifactId = writeLog(run, stepName, artifactLog);
                return StepOutcome.failed(
                        stepName, executed, result.exitCode(), artifactLog, now() - started, artifactId);
            }
        }
        String artifactId = writeLog(run, stepName, log.toString());
        return StepOutcome.succeeded(stepName, executed, lastExit == null ? 0 : lastExit, now() - started, artifactId);
    }

    private void saveSucceededOrSkipped(
            HostVerificationRun run,
            HostVerificationStepName stepName,
            List<String> planned,
            StepOutcome outcome
    ) {
        if (planned == null || planned.isEmpty()) {
            saveSkippedStep(run.runId(), stepName, List.of(), "no commands");
            return;
        }
        store.saveStep(new HostVerificationStep(
                run.runId(),
                stepName,
                HostVerificationStepStatus.SUCCEEDED,
                outcome.commands(),
                outcome.exitCode(),
                outcome.durationMillis(),
                outcome.logArtifactId(),
                ""
        ));
    }

    private void saveSkippedStep(String runId, HostVerificationStepName stepName, List<String> commands, String reason) {
        store.saveStep(new HostVerificationStep(
                runId,
                stepName,
                HostVerificationStepStatus.SKIPPED,
                commands,
                null,
                0L,
                "",
                reason
        ));
    }

    private HostVerificationRun failFromOutcome(
            HostVerificationRun run,
            HostVerificationStatus expected,
            StepOutcome outcome
    ) {
        FailureClass classified = classify(outcome);
        // 廉价返工把 run.errorMessage 注入编码上下文，附上日志尾以免只看到 exit code
        String message = withLogTail(classified.message(), outcome.output());
        store.saveStep(new HostVerificationStep(
                run.runId(),
                outcome.stepName(),
                HostVerificationStepStatus.FAILED,
                outcome.commands(),
                outcome.exitCode(),
                outcome.durationMillis(),
                outcome.logArtifactId(),
                message
        ));
        return transition(run, expected, classified.status(), classified.category(), message);
    }

    private FailureClass classify(StepOutcome outcome) {
        if (outcome.timedOut()) {
            return new FailureClass(
                    "QA_INFRASTRUCTURE",
                    HostVerificationStatus.FAILED_NEEDS_HUMAN,
                    "host verification timed out"
            );
        }
        String output = outcome.output() == null ? "" : outcome.output().toLowerCase(Locale.ROOT);
        Integer exit = outcome.exitCode();
        if (exit != null && exit == 127 || looksLikeMissingCommand(output)) {
            return new FailureClass(
                    "ENVIRONMENT",
                    HostVerificationStatus.FAILED_NEEDS_HUMAN,
                    "command not found (exit " + exit + ")"
            );
        }
        if (looksLikePermission(output) || looksLikeNetworkInstall(output)) {
            return new FailureClass(
                    "ENVIRONMENT",
                    HostVerificationStatus.FAILED_NEEDS_HUMAN,
                    summarizeFailure(outcome)
            );
        }
        return new FailureClass(
                "PRODUCT_DEFECT",
                HostVerificationStatus.FAILED_RETRYABLE,
                summarizeFailure(outcome)
        );
    }

    private static boolean looksLikeMissingCommand(String output) {
        return output.contains("command not found") || output.contains("no such file or directory");
    }

    private static boolean looksLikePermission(String output) {
        return output.contains("permission denied")
                || output.contains("eacces")
                || output.contains("eperm")
                || output.contains("operation not permitted");
    }

    private static boolean looksLikeNetworkInstall(String output) {
        return output.contains("econnrefused")
                || output.contains("econnreset")
                || output.contains("enotfound")
                || output.contains("eai_again")
                || output.contains("etimedout")
                || output.contains("npm err! network")
                || output.contains("could not resolve host")
                || output.contains("network is unreachable");
    }

    private static String summarizeFailure(StepOutcome outcome) {
        String command = outcome.commands().isEmpty() ? "command" : outcome.commands().getLast();
        return command + " exited " + outcome.exitCode();
    }

    private QaValidationProfile resolveQaProfile(RdRequirementTask task) {
        if (qaValidationProfileService == null) {
            return null;
        }
        return qaValidationProfileService.resolve(task.taskId(), task.projectId())
                .profile()
                .orElse(null);
    }

    private static String withLogTail(String summary, String output) {
        String safeSummary = summary == null ? "" : summary.strip();
        String safeOutput = output == null ? "" : output.strip();
        if (safeOutput.isBlank()) {
            return clip(safeSummary);
        }
        String combined = safeSummary.isBlank() ? safeOutput : safeSummary + System.lineSeparator() + safeOutput;
        return clip(combined);
    }

    private static String clip(String value) {
        if (value.length() <= MAX_ERROR_MESSAGE_CHARS) {
            return value;
        }
        return value.substring(0, MAX_ERROR_MESSAGE_CHARS) + "...(truncated)";
    }

    private String writeLog(HostVerificationRun run, HostVerificationStepName stepName, String content) {
        Path root = evidenceRoot == null ? runWorkspaceFallback() : evidenceRoot;
        String folder = stepName == HostVerificationStepName.BUILD ? "build" : "static";
        String relativePath = "verify-evidence/" + folder + "/" + run.runId() + ".log";
        Path file = root.resolve(relativePath);
        byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to write host verification log: " + file, exception);
        }
        String artifactId = nextId();
        String sha256 = sha256(bytes);
        store.appendArtifact(new HostVerificationArtifact(
                artifactId,
                run.taskId(),
                run.runId(),
                stepName == HostVerificationStepName.BUILD ? "VERIFY_BUILD_LOG" : "VERIFY_STATIC_LOG",
                relativePath,
                file.toUri().toString(),
                "text/plain",
                bytes.length,
                sha256,
                now()
        ));
        return artifactId;
    }

    private Path runWorkspaceFallback() {
        return Path.of(System.getProperty("java.io.tmpdir"), "rd-bot", "host-verify");
    }

    private HostVerificationRun newRun(
            RdRequirementTask task,
            AgentStageRun codingStage,
            int remediationCountAlreadyUsed,
            boolean docsOnly
    ) {
        List<HostVerificationRun> existing = store.listByTask(task.taskId());
        int attemptNo = existing.stream().mapToInt(HostVerificationRun::attemptNo).max().orElse(0) + 1;
        String parentRunId = existing.stream()
                .max(Comparator.comparingInt(HostVerificationRun::attemptNo))
                .map(HostVerificationRun::runId)
                .orElse("");
        return new HostVerificationRun(
                nextId(),
                task.taskId(),
                codingStage.stageRunId(),
                parentRunId,
                attemptNo,
                HostVerificationStatus.CREATED,
                docsOnly,
                "",
                "",
                remediationCountAlreadyUsed,
                now(),
                0L,
                0L
        );
    }

    private HostVerificationRun transition(
            HostVerificationRun current,
            HostVerificationStatus expected,
            HostVerificationStatus target,
            String failureCategory,
            String errorMessage
    ) {
        return store.transition(current.runId(), expected, target, failureCategory, errorMessage, now());
    }

    private static void requireTaskAndStage(RdRequirementTask task, AgentStageRun codingStage) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        if (codingStage == null) {
            throw new IllegalArgumentException("codingStage must not be null");
        }
        if (!task.taskId().equals(codingStage.taskId())) {
            throw new IllegalArgumentException(
                    "codingStage.taskId must match task.taskId: " + codingStage.taskId() + " vs " + task.taskId());
        }
    }

    private String nextId() {
        return Long.toString(idGenerator.getAsLong());
    }

    private long now() {
        return clock.getAsLong();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /**
     * Process-execution seam so unit tests never start real processes.
     */
    @FunctionalInterface
    public interface CommandExecutor {

        /**
         * Runs one allowlisted command in {@code workingDirectory}.
         *
         * @param command           command line
         * @param workingDirectory  repository root
         * @param timeout           remaining wall-clock budget
         * @return exit code, output, and timeout flag
         */
        HostVerificationCommandResult run(String command, Path workingDirectory, Duration timeout);
    }

    private record FailureClass(String category, HostVerificationStatus status, String message) {
    }

    private record StepOutcome(
            HostVerificationStepName stepName,
            List<String> commands,
            Integer exitCode,
            long durationMillis,
            String logArtifactId,
            String output,
            boolean timedOut,
            boolean skipped
    ) {
        private static StepOutcome skipped(HostVerificationStepName stepName) {
            return new StepOutcome(stepName, List.of(), null, 0L, "", "", false, true);
        }

        private static StepOutcome succeeded(
                HostVerificationStepName stepName,
                List<String> commands,
                int exitCode,
                long duration,
                String artifactId
        ) {
            return new StepOutcome(stepName, List.copyOf(commands), exitCode, duration, artifactId, "", false, false);
        }

        private static StepOutcome failed(
                HostVerificationStepName stepName,
                List<String> commands,
                int exitCode,
                String output,
                long duration,
                String artifactId
        ) {
            return new StepOutcome(
                    stepName, List.copyOf(commands), exitCode, duration, artifactId, output, false, false);
        }

        private static StepOutcome timeout(
                HostVerificationStepName stepName,
                List<String> commands,
                String output,
                long duration,
                String artifactId
        ) {
            return new StepOutcome(stepName, List.copyOf(commands), -1, duration, artifactId, output, true, false);
        }

        private boolean successful() {
            return skipped || (!timedOut && (exitCode == null || exitCode == 0));
        }
    }
}
