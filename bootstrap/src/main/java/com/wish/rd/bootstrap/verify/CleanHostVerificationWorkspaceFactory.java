package com.wish.rd.bootstrap.verify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.requirement.verify.HostVerificationWorkspaceFactory;
import com.wish.rd.exec.repair.execution.model.RepairInputAttachment;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.oracle.HostVerifierWorkspaceFactory;
import com.wish.rd.exec.repair.oracle.model.HostVerifierWorkspace;
import com.wish.rd.exec.repair.oracle.model.HostVerifierWorkspaceRequest;
import com.wish.rd.rag.runtime.model.RdRequirementTask;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Replays the Coding candidate patch into a clean host-verification checkout.
 *
 * <p>The synthetic {@link RepairJobCommand} sets {@code agentRole=QA_AGENT} only because
 * {@code CleanHostVerifierWorkspaceFactory} requires that field. This is a Host-created
 * replay command, not a QA agent dispatch.
 *
 * <p>Calls {@link HostVerifierWorkspaceFactory#create} with {@code runtimeRequired=false}
 * so npm/dev servers are not started. Does not delete {@code /work/cache} or
 * {@code node_modules}.
 */
public final class CleanHostVerificationWorkspaceFactory implements HostVerificationWorkspaceFactory {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CANDIDATE_PATCH_NAME = "candidate-patch.diff";
    private static final String CANDIDATE_PATCH_MIME = "text/x-diff";
    private static final String CANDIDATE_PATCH_WORK_PATH = "/work/input/attachments/candidate-patch.diff";

    private final HostVerifierWorkspaceFactory verifierWorkspaceFactory;
    private final HostVerificationPatchSource patchSource;

    /**
     * Creates the host-verification replay wrapper.
     *
     * @param verifierWorkspaceFactory clean verifier factory; production is
     *                                 {@code CleanHostVerifierWorkspaceFactory}
     * @param patchSource              coding-stage candidate patch
     */
    public CleanHostVerificationWorkspaceFactory(
            HostVerifierWorkspaceFactory verifierWorkspaceFactory,
            HostVerificationPatchSource patchSource
    ) {
        this.verifierWorkspaceFactory = Objects.requireNonNull(
                verifierWorkspaceFactory, "verifierWorkspaceFactory must not be null");
        this.patchSource = Objects.requireNonNull(patchSource, "patchSource must not be null");
    }

    /**
     * Replays the verified coding patch and returns the repository root.
     *
     * @param task        requirement task
     * @param codingStage coding stage that produced the candidate
     * @return replayed repository directory
     * @throws IllegalArgumentException when task or stage is missing or mismatched
     * @throws IllegalStateException    when the patch is missing or replay fails
     */
    @Override
    public Path prepare(RdRequirementTask task, AgentStageRun codingStage) {
        requireTaskAndStage(task, codingStage);
        HostVerificationCandidatePatch patch = requirePatch(task, codingStage);
        RepairJobCommand command = replayCommand(task, codingStage, patch);
        // 宿主验证只需要干净 checkout，禁止拉起 npm/dev
        HostVerifierWorkspaceRequest request = new HostVerifierWorkspaceRequest(
                task.taskId(),
                codingStage.stageRunId(),
                "CURRENT",
                false
        );
        try {
            HostVerifierWorkspace workspace = verifierWorkspaceFactory.create(command, request);
            return workspace.workspaceRoot();
        } catch (IllegalStateException | IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "failed to replay coding candidate-patch.diff for host verification", exception);
        }
    }

    private HostVerificationCandidatePatch requirePatch(RdRequirementTask task, AgentStageRun codingStage) {
        HostVerificationCandidatePatch patch;
        try {
            patch = patchSource.load(task, codingStage);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "coding stage " + codingStage.stageRunId() + " has no verified candidate-patch.diff",
                    exception
            );
        }
        if (patch == null || !patch.isPresent()) {
            throw new IllegalStateException(
                    "coding stage " + codingStage.stageRunId() + " has no verified candidate-patch.diff");
        }
        return patch;
    }

    private RepairJobCommand replayCommand(
            RdRequirementTask task,
            AgentStageRun codingStage,
            HostVerificationCandidatePatch patch
    ) {
        byte[] content = patch.content();
        String digest = sha256(content);
        if (!patch.sha256Hex().isBlank() && !digest.endsWith(patch.sha256Hex())) {
            throw new IllegalStateException(
                    "coding candidate-patch.diff digest does not match persisted sha256");
        }
        String manifest = handoffManifest(digest, content.length);
        Map<String, String> context = new LinkedHashMap<>();
        context.put("workflowTaskId", task.taskId());
        context.put("stageRunId", codingStage.stageRunId());
        // CleanHostVerifierWorkspaceFactory 硬性要求 agentRole=QA_AGENT；这是宿主重放，不是 QA 派发
        context.put("agentRole", "QA_AGENT");
        context.put("hostVerificationReplay", "true");
        context.put("upstreamHandoffManifestJson", manifest);

        Map<String, String> policy = new LinkedHashMap<>();
        policy.put("repositoryDeliveryMode", "LOCAL_ONLY");
        policy.put("applyCandidatePatch", "true");

        return new RepairJobCommand(
                "host-verify-" + task.taskId(),
                task.taskId() + "-host-verify",
                task.taskId(),
                task.title(),
                "Host-owned verification replay",
                task.repositoryUrl(),
                task.repoOwner(),
                task.repoName(),
                task.baseBranch(),
                task.workBranch(),
                context,
                policy,
                List.of(new RepairInputAttachment(CANDIDATE_PATCH_NAME, CANDIDATE_PATCH_MIME, content))
        );
    }

    private static String handoffManifest(String digest, int bytes) {
        List<Map<String, Object>> entries = List.of(Map.of(
                "sourceRole", "CODING_AGENT",
                "targetRole", "QA_AGENT",
                "path", CANDIDATE_PATCH_WORK_PATH,
                "sha256", digest,
                "bytes", bytes,
                "applied", true
        ));
        try {
            return OBJECT_MAPPER.writeValueAsString(entries);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize host verification handoff manifest", exception);
        }
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

    private static String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
