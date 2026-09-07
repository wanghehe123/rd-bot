package com.wish.rd.engine.requirement.query;

import com.wish.rd.engine.agent.model.AgentStageRun;

/**
 * Raw stage-result inputs gathered inside one read transaction.
 *
 * @param stage owned stage
 * @param commandId bound command, or blank
 * @param finalizationId command+attempt identity of the finalized row, or blank
 * @param finalizedResultJson finalized result_json, or blank
 * @param artifactId result artifact id, or blank
 * @param artifactPreview stored preview, or blank
 * @param previewTruncated whether the preview is shorter than the stored content
 */
public record StageResultSnapshot(
        AgentStageRun stage,
        String commandId,
        String finalizationId,
        String finalizedResultJson,
        String artifactId,
        String artifactPreview,
        boolean previewTruncated
) {
    public StageResultSnapshot {
        if (stage == null) {
            throw new IllegalArgumentException("stage is required");
        }
        commandId = commandId == null ? "" : commandId.strip();
        finalizationId = finalizationId == null ? "" : finalizationId.strip();
        finalizedResultJson = finalizedResultJson == null ? "" : finalizedResultJson;
        artifactId = artifactId == null ? "" : artifactId.strip();
        artifactPreview = artifactPreview == null ? "" : artifactPreview;
    }
}
