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
 */
public record RequirementExecutionRequest(
        String taskId,
        RdRequirementTask task,
        List<TaskMaterial> materials,
        String prompt,
        AgentRole role,
        String roleContextJson,
        boolean pullRequestRequired,
        String upstreamResultJson
) {

    public RequirementExecutionRequest(
            String taskId,
            RdRequirementTask task,
            List<TaskMaterial> materials,
            String prompt
    ) {
        this(taskId, task, materials, prompt, AgentRole.CODING_AGENT, "{}", false, "[]");
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
    }
}
