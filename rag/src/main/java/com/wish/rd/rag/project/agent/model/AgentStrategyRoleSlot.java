package com.wish.rd.rag.project.agent.model;

import java.util.Locale;
import java.util.Objects;

/** One delivery-role slot inside a project agent strategy. */
public record AgentStrategyRoleSlot(
        String role,
        AgentRuntimeType runtimeType,
        String providerProfileId,
        String modelOverride,
        String extensionSetId,
        long extensionSetVersion,
        String toolPolicyId,
        long toolPolicyVersion,
        AgentStrategyImageMode imageMode,
        String image,
        String dockerfileName,
        String dockerfileSha256,
        String dockerfileArtifactUri,
        String dockerfileText
) {

    public AgentStrategyRoleSlot {
        role = text(role).toUpperCase(Locale.ROOT);
        Objects.requireNonNull(runtimeType, "runtimeType must not be null");
        providerProfileId = text(providerProfileId);
        modelOverride = text(modelOverride);
        extensionSetId = text(extensionSetId);
        extensionSetVersion = Math.max(0L, extensionSetVersion);
        toolPolicyId = text(toolPolicyId);
        toolPolicyVersion = toolPolicyVersion <= 0L ? 1L : toolPolicyVersion;
        imageMode = imageMode == null ? AgentStrategyImageMode.LOCAL_DEFAULT : imageMode;
        image = text(image);
        dockerfileName = text(dockerfileName);
        dockerfileSha256 = text(dockerfileSha256);
        dockerfileArtifactUri = text(dockerfileArtifactUri);
        dockerfileText = dockerfileText == null ? "" : dockerfileText;
    }

    /** Returns a copy without Dockerfile body, for list payloads. */
    public AgentStrategyRoleSlot withoutDockerfileText() {
        if (dockerfileText.isEmpty()) {
            return this;
        }
        return new AgentStrategyRoleSlot(
                role,
                runtimeType,
                providerProfileId,
                modelOverride,
                extensionSetId,
                extensionSetVersion,
                toolPolicyId,
                toolPolicyVersion,
                imageMode,
                image,
                dockerfileName,
                dockerfileSha256,
                dockerfileArtifactUri,
                ""
        );
    }

    /**
     * Returns this slot with custom image metadata replaced.
     *
     * @param nextImage resolved image tag, may be blank for Pi persist-only
     * @param nextDockerfileName original file name
     * @param nextSha256 content hash
     * @param nextArtifactUri stored artifact URI
     * @param nextDockerfileText raw Dockerfile text
     * @return updated slot
     */
    public AgentStrategyRoleSlot withCustomImage(
            String nextImage,
            String nextDockerfileName,
            String nextSha256,
            String nextArtifactUri,
            String nextDockerfileText
    ) {
        return new AgentStrategyRoleSlot(
                role,
                runtimeType,
                providerProfileId,
                modelOverride,
                extensionSetId,
                extensionSetVersion,
                toolPolicyId,
                toolPolicyVersion,
                AgentStrategyImageMode.CUSTOM,
                nextImage,
                nextDockerfileName,
                nextSha256,
                nextArtifactUri,
                nextDockerfileText
        );
    }

    /** Returns this slot restored to the host default image. */
    public AgentStrategyRoleSlot withLocalDefaultImage() {
        return new AgentStrategyRoleSlot(
                role,
                runtimeType,
                providerProfileId,
                modelOverride,
                extensionSetId,
                extensionSetVersion,
                toolPolicyId,
                toolPolicyVersion,
                AgentStrategyImageMode.LOCAL_DEFAULT,
                "",
                "",
                "",
                "",
                ""
        );
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
