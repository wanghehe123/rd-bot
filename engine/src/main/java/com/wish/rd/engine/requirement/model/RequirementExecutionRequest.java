package com.wish.rd.engine.requirement.model;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.TaskMaterial;

import java.util.List;

/**
 * 需求交付执行请求。
 *
 * @param taskId    任务 ID
 * @param task      需求任务快照
 * @param materials 需求输入材料
 * @param prompt    执行器 Prompt
 * @param role                当前 Agent 角色
 * @param roleContextJson     当前角色上下文包 JSON
 * @param pullRequestRequired 是否请求阶段执行器创建 PR；需求交付链路必须为 false
 * @param upstreamResultJson  上游角色阶段结果 JSON
 * @param stageRunId          当前角色阶段运行 ID
 * @param executionProfileSnapshotId 已冻结的执行 Profile 快照 ID，可为空表示兼容旧调用
 * @param inputManifestHash   冻结的 role execution input manifest hash，可为空
 * @param contextPolicyHash   冻结的 runtime context policy hash，可为空
 * @param inputManifestJson   冻结的 role execution input manifest JSON，可为空
 * @param contextPolicyJson   冻结的 runtime context policy JSON，可为空
 */
public record RequirementExecutionRequest(
        String taskId,
        RdRequirementTask task,
        List<TaskMaterial> materials,
        String prompt,
        AgentRole role,
        String roleContextJson,
        boolean pullRequestRequired,
        String upstreamResultJson,
        String stageRunId,
        String executionProfileSnapshotId,
        String inputManifestHash,
        String contextPolicyHash,
        String inputManifestJson,
        String contextPolicyJson
) {

    public RequirementExecutionRequest(
            String taskId,
            RdRequirementTask task,
            List<TaskMaterial> materials,
            String prompt,
            AgentRole role,
            String roleContextJson,
            boolean pullRequestRequired,
            String upstreamResultJson
    ) {
        this(
                taskId, task, materials, prompt, role, roleContextJson, pullRequestRequired,
                upstreamResultJson, "", "", "", "", "", ""
        );
    }

    public RequirementExecutionRequest(
            String taskId,
            RdRequirementTask task,
            List<TaskMaterial> materials,
            String prompt,
            AgentRole role,
            String roleContextJson,
            boolean pullRequestRequired,
            String upstreamResultJson,
            String stageRunId,
            String executionProfileSnapshotId
    ) {
        this(
                taskId, task, materials, prompt, role, roleContextJson, pullRequestRequired,
                upstreamResultJson, stageRunId, executionProfileSnapshotId, "", "", "", ""
        );
    }

    public RequirementExecutionRequest(
            String taskId,
            RdRequirementTask task,
            List<TaskMaterial> materials,
            String prompt,
            AgentRole role,
            String roleContextJson,
            boolean pullRequestRequired,
            String upstreamResultJson,
            String stageRunId,
            String executionProfileSnapshotId,
            String inputManifestHash,
            String contextPolicyHash
    ) {
        this(
                taskId, task, materials, prompt, role, roleContextJson, pullRequestRequired,
                upstreamResultJson, stageRunId, executionProfileSnapshotId,
                inputManifestHash, contextPolicyHash, "", ""
        );
    }

    public RequirementExecutionRequest(
            String taskId,
            RdRequirementTask task,
            List<TaskMaterial> materials,
            String prompt
    ) {
        this(taskId, task, materials, prompt, AgentRole.CODING_AGENT, "{}", false, "[]", "", "", "", "", "", "");
    }

    public RequirementExecutionRequest {
        taskId = taskId == null ? "" : taskId.strip();
        materials = materials == null ? List.of() : List.copyOf(materials);
        prompt = prompt == null ? "" : prompt;
        role = role == null ? AgentRole.CODING_AGENT : role;
        roleContextJson = roleContextJson == null || roleContextJson.isBlank() ? "{}" : roleContextJson.strip();
        upstreamResultJson = upstreamResultJson == null || upstreamResultJson.isBlank()
                ? "[]"
                : upstreamResultJson.strip();
        stageRunId = stageRunId == null ? "" : stageRunId.strip();
        executionProfileSnapshotId = executionProfileSnapshotId == null
                ? ""
                : executionProfileSnapshotId.strip();
        inputManifestHash = inputManifestHash == null ? "" : inputManifestHash.strip();
        contextPolicyHash = contextPolicyHash == null ? "" : contextPolicyHash.strip();
        inputManifestJson = inputManifestJson == null ? "" : inputManifestJson.strip();
        contextPolicyJson = contextPolicyJson == null ? "" : contextPolicyJson.strip();
    }
}
