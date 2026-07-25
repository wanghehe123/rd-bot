package com.wish.rd.rag.project.runtime;

import com.wish.rd.rag.project.runtime.impl.InMemoryProjectRuntimeProfileStore;
import com.wish.rd.rag.project.runtime.model.ProjectRuntimeProfile;
import com.wish.rd.rag.project.runtime.model.ProjectRuntimeProfileCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectRuntimeProfileServiceTest {

    @Test
    void shouldPersistAndResolveOnlyVerifiedClaudeCodeRoleImage() {
        ProjectRuntimeProfileService service = new ProjectRuntimeProfileService(
                new InMemoryProjectRuntimeProfileStore(),
                ignored -> { }
        );

        ProjectRuntimeProfile saved = service.save(new ProjectRuntimeProfileCommand(
                "7486000000000000001",
                "CODING_AGENT",
                "CLAUDE_CODE",
                "rd-bot/project-7486000000000000001-coding:verified",
                "s3://rd-project-runtime-dockerfiles/coding.Dockerfile",
                "a".repeat(64),
                "Dockerfile.python",
                ProjectRuntimeProfileService.RUNTIME_PROFILE_CONTRACT_MARKER
                        + "; claude 2.1.0; entrypoint and workspace contract verified"
        ));

        assertEquals("VERIFIED", saved.validationStatus());
        assertEquals("rd-bot/project-7486000000000000001-coding:verified",
                service.resolveVerified("7486000000000000001", "CODING_AGENT").orElseThrow().image());
        assertEquals(1, service.list("7486000000000000001").size());
    }

    @Test
    void shouldRequireTheCurrentRuntimeContractBeforeResolvingAStoredProfile() {
        ProjectRuntimeProfileService service = new ProjectRuntimeProfileService(
                new InMemoryProjectRuntimeProfileStore(),
                ignored -> { }
        );
        service.save(new ProjectRuntimeProfileCommand(
                "7486000000000000003",
                "QA_AGENT",
                "CLAUDE_CODE",
                "rd-bot/project-7486000000000000003-qa:legacy",
                "s3://rd-project-runtime-dockerfiles/qa.Dockerfile",
                "c".repeat(64),
                "Dockerfile",
                "claude runtime smoke verified"
        ));

        assertTrue(service.resolveVerified("7486000000000000003", "QA_AGENT").isEmpty());
    }

    @Test
    void shouldRejectUnsupportedAgentTypeBeforePersistingRuntimeProfile() {
        ProjectRuntimeProfileService service = new ProjectRuntimeProfileService(
                new InMemoryProjectRuntimeProfileStore(),
                ignored -> { }
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.save(
                new ProjectRuntimeProfileCommand(
                        "7486000000000000002",
                        "CODING_AGENT",
                        "OPENAI_CODEX",
                        "rd-bot/not-allowed:latest",
                        "s3://rd-project-runtime-dockerfiles/Dockerfile",
                        "b".repeat(64),
                        "Dockerfile",
                        "not relevant"
                )
        ));

        assertTrue(exception.getMessage().contains("agentType must be CLAUDE_CODE"));
    }
}
