package com.wish.rd.engine.requirement.query;

/**
 * Frozen stage-result HTTP contract.
 *
 * @param taskId owning task
 * @param stageRunId stage run
 * @param role agent role
 * @param attemptNo agent attempt
 * @param artifactId result artifact, or null
 * @param commandId bound command, or null
 * @param finalizationId finalized marker identity, or null
 * @param source FINALIZATION_RESULT, ARTIFACT_PREVIEW, or UNAVAILABLE
 * @param available whether a role body is readable
 * @param unavailableReason reason when incomplete
 * @param contentType JSON
 * @param content role body or preview
 * @param truncated whether content is truncated
 * @param contentSha256 hash of the returned readable body
 * @param downloadPath controlled download path, or null
 */
public record StageResultView(
        String taskId,
        String stageRunId,
        String role,
        int attemptNo,
        String artifactId,
        String commandId,
        String finalizationId,
        String source,
        boolean available,
        String unavailableReason,
        String contentType,
        String content,
        boolean truncated,
        String contentSha256,
        String downloadPath
) {
    public static final String SOURCE_FINALIZATION = "FINALIZATION_RESULT";
    public static final String SOURCE_PREVIEW = "ARTIFACT_PREVIEW";
    public static final String SOURCE_UNAVAILABLE = "UNAVAILABLE";
    public static final String FULL_RESULT_MISSING = "FULL_RESULT_NOT_PERSISTED_OR_NOT_BOUND";
    public static final int PREVIEW_CHARS = 20_000;
}
