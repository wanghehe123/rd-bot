package com.wish.rd.rag.project.runtime.model;

/** Input persisted after a Dockerfile has been built and smoke-verified. */
public record ProjectRuntimeProfileCommand(
        String projectId,
        String role,
        String agentType,
        String image,
        String dockerfileArtifactUri,
        String dockerfileSha256,
        String dockerfileName,
        String validationSummary
) {

    public ProjectRuntimeProfileCommand {
        projectId = text(projectId);
        role = text(role).toUpperCase(java.util.Locale.ROOT);
        agentType = text(agentType).toUpperCase(java.util.Locale.ROOT);
        image = text(image);
        dockerfileArtifactUri = text(dockerfileArtifactUri);
        dockerfileSha256 = text(dockerfileSha256).toLowerCase(java.util.Locale.ROOT);
        dockerfileName = text(dockerfileName);
        validationSummary = text(validationSummary);
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
