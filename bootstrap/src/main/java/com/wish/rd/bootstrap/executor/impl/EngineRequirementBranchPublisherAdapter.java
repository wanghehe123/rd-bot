package com.wish.rd.bootstrap.executor.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wish.rd.engine.requirement.RequirementBranchPublisherPort;
import com.wish.rd.engine.requirement.model.RequirementBranchPublication;
import com.wish.rd.engine.requirement.model.RequirementBranchPublishCommand;
import com.wish.rd.exec.repair.docker.RepairWorkspaceFactory;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort.RepositoryOperationResult;
import com.wish.rd.exec.repair.docker.model.RepairWorkspace;
import com.wish.rd.exec.repair.execution.model.RepairInputAttachment;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 需求交付复核通过后、创建 PR 之前的工作分支推送适配器。
 *
 * <p>复用与 QA 一致的候选补丁通道：从已复核交付结果中取回 CODING_AGENT 产出的候选补丁，
 * 在隔离工作区里 checkout 基线分支、应用补丁，再 commit 并 push 到远端工作分支。
 * 这样 {@code gh create PR} 的 head 分支在远端已经存在，避免 422。
 */
public final class EngineRequirementBranchPublisherAdapter implements RequirementBranchPublisherPort {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CODING_ROLE = "CODING_AGENT";
    private static final String QA_ROLE = "QA_AGENT";

    private final RoleHandoffAttachmentResolver handoffAttachmentResolver;
    private final RepairWorkspaceFactory workspaceFactory;
    private final RepairWorkspaceRepositoryPort workspaceRepository;

    public EngineRequirementBranchPublisherAdapter(
            RoleHandoffAttachmentResolver handoffAttachmentResolver,
            RepairWorkspaceFactory workspaceFactory,
            RepairWorkspaceRepositoryPort workspaceRepository
    ) {
        this.handoffAttachmentResolver = Objects.requireNonNull(
                handoffAttachmentResolver, "handoffAttachmentResolver must not be null");
        this.workspaceFactory = Objects.requireNonNull(workspaceFactory, "workspaceFactory must not be null");
        this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository must not be null");
    }

    @Override
    public RequirementBranchPublication publishBranch(RequirementBranchPublishCommand command) {
        if (command == null) {
            return RequirementBranchPublication.failure("", "branch publish command must not be null");
        }
        if (command.repositoryUrl().isBlank() || command.baseBranch().isBlank() || command.workBranch().isBlank()) {
            return RequirementBranchPublication.failure(
                    command.taskId(), "repository, base branch and work branch must not be blank");
        }
        RepairInputAttachment candidatePatch;
        try {
            candidatePatch = resolveCandidatePatch(command.deliveryResultJson());
        } catch (RuntimeException exception) {
            return RequirementBranchPublication.failure(
                    command.taskId(), "candidate patch resolution failed: " + safe(exception.getMessage()));
        }
        if (candidatePatch == null) {
            return RequirementBranchPublication.failure(
                    command.taskId(), "reviewed delivery result does not carry a verified candidate patch");
        }
        RepairJobCommand job = new RepairJobCommand(
                "branch-publish-" + command.taskId(),
                command.taskId() + "-publish",
                command.taskId(),
                command.title(),
                "",
                command.repositoryUrl(),
                command.repoOwner(),
                command.repoName(),
                command.baseBranch(),
                command.workBranch(),
                Map.of(),
                Map.of(
                        "bridge", "engine-requirement-branch-publisher",
                        "repositoryPublishRequired", "true",
                        "repositoryDeliveryMode", "PUBLISH",
                        "applyCandidatePatch", "true"
                ),
                List.of(candidatePatch)
        );
        try {
            RepairWorkspace workspace = workspaceFactory.create(job);
            workspaceRepository.prepare(job, workspace);
            RepositoryOperationResult publishResult = workspaceRepository.publish(job, workspace);
            Map<String, String> metadata = publishResult == null ? Map.of() : publishResult.metadataJson();
            return RequirementBranchPublication.success(
                    command.taskId(),
                    metadata.getOrDefault("commitSha", ""),
                    metadataJson(metadata)
            );
        } catch (Exception exception) {
            return RequirementBranchPublication.failure(
                    command.taskId(), "work branch push failed: " + safe(exception.getMessage()));
        }
    }

    /**
     * 从已复核交付结果里取回 CODING_AGENT 候选补丁。构造与 QA 通道一致的 handoff 结构，
     * 复用 {@link RoleHandoffAttachmentResolver} 完成 S3 拉取与完整性校验。
     */
    private RepairInputAttachment resolveCandidatePatch(String deliveryResultJson) {
        ObjectNode descriptor = candidatePatchDescriptor(deliveryResultJson);
        if (descriptor == null) {
            return null;
        }
        ObjectNode stage = OBJECT_MAPPER.createObjectNode();
        stage.put("role", CODING_ROLE);
        stage.set("candidatePatch", descriptor);
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.putArray("stages").add(stage);
        String handoffJson;
        try {
            handoffJson = OBJECT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot build candidate patch handoff", exception);
        }
        List<RepairInputAttachment> attachments = handoffAttachmentResolver.resolve(QA_ROLE, handoffJson);
        return attachments.stream()
                .filter(attachment -> "candidate-patch.diff".equals(attachment.filename()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 抽取候选补丁描述符，兼容两种落库形态：
     * <ul>
     *   <li>恢复态：阶段节点直接带 {@code candidatePatch} 对象；</li>
     *   <li>首发态：阶段节点的 {@code resultJson} 内含 {@code stageArtifacts} 里的 PATCH_DIFF。</li>
     * </ul>
     */
    private ObjectNode candidatePatchDescriptor(String deliveryResultJson) {
        if (deliveryResultJson == null || deliveryResultJson.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(deliveryResultJson);
        } catch (JsonProcessingException exception) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        for (String stagesField : List.of("multiAgentStages", "stages")) {
            JsonNode stages = root.path(stagesField);
            if (!stages.isArray()) {
                continue;
            }
            for (JsonNode stage : stages) {
                if (stage == null || !stage.isObject()) {
                    continue;
                }
                ObjectNode fromManifest = descriptorFromManifest(stage.path("candidatePatch"));
                if (fromManifest != null) {
                    return fromManifest;
                }
                ObjectNode fromArtifacts = descriptorFromStageArtifacts(
                        stage.path("role").asText(""), embeddedRoleResult(stage.path("resultJson")));
                if (fromArtifacts != null) {
                    return fromArtifacts;
                }
            }
        }
        // 顶层直接携带 stageArtifacts（单角色结果形态）时兜底扫描。
        return descriptorFromStageArtifacts(CODING_ROLE, root);
    }

    private ObjectNode descriptorFromManifest(JsonNode candidatePatch) {
        if (candidatePatch == null || !candidatePatch.isObject()) {
            return null;
        }
        String artifactUri = safe(candidatePatch.path("artifactUri").asText(""));
        String sha256 = safe(candidatePatch.path("sha256").asText(""));
        long bytes = candidatePatch.path("bytes").asLong(-1L);
        if (!artifactUri.startsWith("s3://") || sha256.isBlank() || bytes <= 0L) {
            return null;
        }
        return descriptor(artifactUri, sha256, bytes);
    }

    private ObjectNode descriptorFromStageArtifacts(String role, JsonNode roleResult) {
        if (!CODING_ROLE.equalsIgnoreCase(safe(role))
                || roleResult == null
                || !roleResult.path("stageArtifacts").isArray()) {
            return null;
        }
        for (JsonNode artifact : roleResult.path("stageArtifacts")) {
            if (artifact == null || !artifact.isObject()
                    || !"PATCH_DIFF".equalsIgnoreCase(safe(artifact.path("type").asText("")))
                    || !"patch.diff".equals(safe(artifact.path("name").asText("")))) {
                continue;
            }
            JsonNode metadata = artifact.path("metadataJson");
            if (!"true".equalsIgnoreCase(safe(metadata.path("candidatePatch").asText("")))) {
                continue;
            }
            String artifactUri = safe(artifact.path("uri").asText(""));
            String sha256 = safe(metadata.path("sha256").asText(""));
            long bytes = metadata.path("bytes").asLong(-1L);
            if (!artifactUri.startsWith("s3://") || sha256.isBlank() || bytes <= 0L) {
                continue;
            }
            return descriptor(artifactUri, sha256, bytes);
        }
        return null;
    }

    private ObjectNode descriptor(String artifactUri, String sha256, long bytes) {
        ObjectNode descriptor = OBJECT_MAPPER.createObjectNode();
        descriptor.put("sourceRole", CODING_ROLE);
        descriptor.put("targetRole", QA_ROLE);
        descriptor.put("artifactName", "patch.diff");
        descriptor.put("artifactUri", artifactUri);
        descriptor.put("sha256", sha256);
        descriptor.put("bytes", bytes);
        return descriptor;
    }

    private JsonNode embeddedRoleResult(JsonNode rawResult) {
        if (rawResult == null || rawResult.isMissingNode() || rawResult.isNull()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        if (rawResult.isObject()) {
            return rawResult;
        }
        if (!rawResult.isTextual()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(safe(rawResult.asText()));
            return parsed != null && parsed.isObject() ? parsed : OBJECT_MAPPER.createObjectNode();
        } catch (JsonProcessingException exception) {
            return OBJECT_MAPPER.createObjectNode();
        }
    }

    private String metadataJson(Map<String, String> metadata) {
        try {
            return OBJECT_MAPPER.writeValueAsString(metadata == null ? Map.of() : new LinkedHashMap<>(metadata));
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
