package com.wish.rd.engine.requirement;

import com.wish.rd.rag.runtime.RdRequirementTask;
import com.wish.rd.rag.runtime.TaskMaterial;

import java.util.List;

/**
 * 需求交付执行请求。
 *
 * @param taskId    任务 ID
 * @param task      需求任务快照
 * @param materials 需求输入材料
 * @param prompt    执行器 Prompt
 */
public record RequirementExecutionRequest(
        String taskId,
        RdRequirementTask task,
        List<TaskMaterial> materials,
        String prompt
) {

    public RequirementExecutionRequest {
        taskId = taskId == null ? "" : taskId.strip();
        materials = materials == null ? List.of() : List.copyOf(materials);
        prompt = prompt == null ? "" : prompt;
    }
}
