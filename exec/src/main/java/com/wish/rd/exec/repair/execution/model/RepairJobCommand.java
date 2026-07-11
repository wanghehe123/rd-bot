package com.wish.rd.exec.repair.execution.model;

import com.wish.rd.exec.repair.execution.ExecutionJsonMaps;
import java.util.Map;
import java.util.List;

/**
 * 修复执行命令，承载 RAG 上下文、工单信息、仓库信息和执行策略。
 *
 * @param repairRecordId 修复记录 ID
 * @param taskId         RD 任务 ID
 * @param ticketId       外部工单 ID
 * @param ticketTitle    外部工单标题
 * @param prompt         执行器输入提示词
 * @param repositoryUrl  目标仓库 URL
 * @param repoOwner      目标仓库 owner
 * @param repoName       目标仓库名称
 * @param baseBranch     基准分支
 * @param workBranch     修复工作分支
 * @param contextJson    RAG 上下文字段
 * @param policyJson     执行策略字段
 * @param attachments    binary input attachments
 */
public record RepairJobCommand(
        String repairRecordId,
        String taskId,
        String ticketId,
        String ticketTitle,
        String prompt,
        String repositoryUrl,
        String repoOwner,
        String repoName,
        String baseBranch,
        String workBranch,
        Map<String, String> contextJson,
        Map<String, String> policyJson,
        List<RepairInputAttachment> attachments
) {

    public RepairJobCommand {
        repairRecordId = requireId(repairRecordId, "repairRecordId");
        taskId = requireId(taskId, "taskId");
        ticketId = normalize(ticketId);
        ticketTitle = normalize(ticketTitle);
        prompt = normalize(prompt);
        repositoryUrl = normalize(repositoryUrl);
        repoOwner = normalize(repoOwner);
        repoName = normalize(repoName);
        baseBranch = normalize(baseBranch);
        workBranch = normalize(workBranch);
        contextJson = ExecutionJsonMaps.copy(contextJson);
        policyJson = ExecutionJsonMaps.copy(policyJson);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** Backward-compatible constructor for jobs without binary inputs. */
    public RepairJobCommand(
            String repairRecordId,
            String taskId,
            String ticketId,
            String ticketTitle,
            String prompt,
            String repositoryUrl,
            String repoOwner,
            String repoName,
            String baseBranch,
            String workBranch,
            Map<String, String> contextJson,
            Map<String, String> policyJson
    ) {
        this(
                repairRecordId, taskId, ticketId, ticketTitle, prompt, repositoryUrl,
                repoOwner, repoName, baseBranch, workBranch, contextJson, policyJson, List.of()
        );
    }

    private static String requireId(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
