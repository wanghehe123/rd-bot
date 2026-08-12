package com.wish.rd.engine.requirement.publication.model;

/**
 * Command that creates or reuses a publication intent before any remote write.
 *
 * @param operationId           unique operation identity
 * @param taskId                RD task id
 * @param stageRunId            publishing stage run id
 * @param baseBranch            target branch
 * @param workBranch            candidate work branch
 * @param candidatePatchSha256  candidate patch identity
 */
public record RequirementPublicationPrepareCommand(
        String operationId,
        String taskId,
        String stageRunId,
        String baseBranch,
        String workBranch,
        String candidatePatchSha256
) {

    public RequirementPublicationPrepareCommand {
        operationId = require(operationId, "operationId");
        taskId = require(taskId, "taskId");
        stageRunId = safe(stageRunId);
        baseBranch = require(baseBranch, "baseBranch");
        workBranch = require(workBranch, "workBranch");
        candidatePatchSha256 = require(candidatePatchSha256, "candidatePatchSha256");
    }

    private static String require(String value, String field) {
        String normalized = safe(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
