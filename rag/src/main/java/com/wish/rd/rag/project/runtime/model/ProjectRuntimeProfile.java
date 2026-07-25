package com.wish.rd.rag.project.runtime.model;

/** A verified, private Docker runtime selected for one project delivery role. */
public record ProjectRuntimeProfile(
        String projectId,
        String role,
        String agentType,
        String image,
        String dockerfileArtifactUri,
        String dockerfileSha256,
        String dockerfileName,
        String validationStatus,
        String validationSummary,
        long createTimeEpochMillis,
        long updateTimeEpochMillis
) {

    public ProjectRuntimeProfile {
        projectId = text(projectId);
        role = text(role).toUpperCase(java.util.Locale.ROOT);
        agentType = text(agentType).toUpperCase(java.util.Locale.ROOT);
        image = text(image);
        dockerfileArtifactUri = text(dockerfileArtifactUri);
        dockerfileSha256 = text(dockerfileSha256).toLowerCase(java.util.Locale.ROOT);
        dockerfileName = text(dockerfileName);
        validationStatus = text(validationStatus).toUpperCase(java.util.Locale.ROOT);
        validationSummary = text(validationSummary);
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
