package com.wish.rd.bootstrap.controller.admin.agent;

import com.wish.rd.bootstrap.executor.impl.ProjectRuntimeProfileUploadService;
import com.wish.rd.rag.project.agent.AgentExecutionProfileService;
import com.wish.rd.rag.project.agent.AgentStrategyProfileService;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentExecutionProfileStore;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentStrategyProfileStore;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.project.agent.model.AgentStrategyImageMode;
import com.wish.rd.rag.project.agent.model.AgentStrategyProfile;
import com.wish.rd.rag.project.agent.model.AgentStrategyRoleSlot;
import com.wish.rd.rag.project.runtime.ProjectRuntimeProfileService;
import com.wish.rd.rag.project.runtime.impl.InMemoryProjectRuntimeProfileStore;
import com.wish.rd.rag.project.runtime.model.ProjectRuntimeProfile;
import com.wish.rd.rag.project.runtime.model.ProjectRuntimeProfileCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentStrategyAdminControllerTest {

    @Test
    void shouldRejectUnauthorizedWritesAndIncompleteRoles() {
        AgentStrategyAdminController controller = controller("runtime-token", false);
        AgentStrategyProfile incomplete = new AgentStrategyProfile(
                "prod",
                "ignored",
                "生产",
                true,
                1L,
                List.of(slot("CODING_AGENT", AgentRuntimeType.PI, "deepseek"))
        );

        ResponseStatusException forbidden = assertThrows(
                ResponseStatusException.class,
                () -> controller.create("project-1", "wrong-token", complete("prod"))
        );
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());

        IllegalArgumentException missingRoles = assertThrows(
                IllegalArgumentException.class,
                () -> controller.create("project-1", "runtime-token", incomplete)
        );
        assertTrue(missingRoles.getMessage().contains("four delivery roles"));

        AgentStrategyProfile saved = controller.create("project-1", "runtime-token", complete("prod"));
        assertEquals(4, saved.roles().size());
        assertEquals("prod", controller.list("project-1").defaultStrategyId());
        assertFalse(controller.list("project-1").agentRuntimeEnabled());
        assertEquals("rd-bot/pi-agent:local", controller.list("project-1").defaultPiImage());
    }

    @Test
    void shouldReturn503WhenMutationTokenIsNotConfigured() {
        AgentStrategyAdminController controller = controller("", false);
        ResponseStatusException unavailable = assertThrows(
                ResponseStatusException.class,
                () -> controller.create("project-1", "runtime-token", complete("prod"))
        );
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getStatusCode());
    }

    @Test
    void shouldBuildClaudeCustomImageThroughExistingUploadService() {
        ProjectRuntimeProfileUploadService uploadService = mock(ProjectRuntimeProfileUploadService.class);
        when(uploadService.upload(eq("project-1"), eq("REQUIREMENT_REVIEWER"), eq("CLAUDE_CODE"), eq("Dockerfile"), any()))
                .thenReturn(new ProjectRuntimeProfile(
                        "project-1",
                        "REQUIREMENT_REVIEWER",
                        "CLAUDE_CODE",
                        "rd-bot/claude-code:verified",
                        "s3://bucket/Dockerfile",
                        "a".repeat(64),
                        "Dockerfile",
                        "VERIFIED",
                        ProjectRuntimeProfileService.RUNTIME_PROFILE_CONTRACT_MARKER,
                        1L,
                        1L
                ));
        AgentStrategyAdminController controller = controller("runtime-token", false, uploadService);
        controller.create("project-1", "runtime-token", complete("prod"));
        MockMultipartFile dockerfile = new MockMultipartFile(
                "dockerfile",
                "Dockerfile",
                "text/plain",
                "FROM claude".getBytes()
        );
        AgentStrategyProfile uploaded = controller.uploadRoleImage(
                "project-1", "prod", "REQUIREMENT_REVIEWER", "runtime-token", dockerfile
        );
        assertEquals(AgentStrategyImageMode.CUSTOM, uploaded.slot("REQUIREMENT_REVIEWER").imageMode());
        assertEquals("rd-bot/claude-code:verified", uploaded.slot("REQUIREMENT_REVIEWER").image());
        verify(uploadService).upload(eq("project-1"), eq("REQUIREMENT_REVIEWER"), eq("CLAUDE_CODE"), eq("Dockerfile"), any());
    }

    @Test
    void shouldDeleteClaudeRuntimeProfileWhenSavingLocalDefault() {
        InMemoryProjectRuntimeProfileStore runtimeStore = new InMemoryProjectRuntimeProfileStore();
        ProjectRuntimeProfileService runtimeService = new ProjectRuntimeProfileService(runtimeStore);
        runtimeService.save(new ProjectRuntimeProfileCommand(
                "project-1",
                "REQUIREMENT_REVIEWER",
                "CLAUDE_CODE",
                "rd-bot/claude-code:custom",
                "s3://bucket/Dockerfile",
                "a".repeat(64),
                "Dockerfile",
                "ok " + ProjectRuntimeProfileService.RUNTIME_PROFILE_CONTRACT_MARKER
        ));
        AgentStrategyAdminController controller = controller(
                "runtime-token",
                false,
                emptyUploadProvider(),
                runtimeService
        );
        controller.create("project-1", "runtime-token", complete("prod"));
        assertTrue(runtimeService.list("project-1").isEmpty());
    }

    @Test
    void shouldPersistPiDockerfileWithoutBuildingAndRejectClaudeUploadWhenBuilderMissing() {
        AgentStrategyAdminController controller = controller("runtime-token", false);
        controller.create("project-1", "runtime-token", complete("prod"));

        MockMultipartFile dockerfile = new MockMultipartFile(
                "dockerfile",
                "Dockerfile",
                "text/plain",
                "FROM pi".getBytes()
        );
        AgentStrategyProfile piUpload = controller.uploadRoleImage(
                "project-1", "prod", "CODING_AGENT", "runtime-token", dockerfile
        );
        assertEquals(AgentStrategyImageMode.CUSTOM, piUpload.slot("CODING_AGENT").imageMode());
        assertEquals("FROM pi", piUpload.slot("CODING_AGENT").dockerfileText());

        ResponseStatusException claudeUnavailable = assertThrows(
                ResponseStatusException.class,
                () -> controller.uploadRoleImage(
                        "project-1",
                        "prod",
                        "REQUIREMENT_REVIEWER",
                        "runtime-token",
                        dockerfile
                )
        );
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, claudeUnavailable.getStatusCode());

        AgentStrategyProfile cleared = controller.clearRoleImage(
                "project-1", "prod", "CODING_AGENT", "runtime-token"
        );
        assertEquals(AgentStrategyImageMode.LOCAL_DEFAULT, cleared.slot("CODING_AGENT").imageMode());
    }

    private static AgentStrategyAdminController controller(String token, boolean agentRuntimeEnabled) {
        return controller(token, agentRuntimeEnabled, emptyUploadProvider(), new ProjectRuntimeProfileService(new InMemoryProjectRuntimeProfileStore()));
    }

    private static AgentStrategyAdminController controller(
            String token,
            boolean agentRuntimeEnabled,
            ProjectRuntimeProfileUploadService uploadService
    ) {
        return controller(token, agentRuntimeEnabled, providerOf(uploadService), new ProjectRuntimeProfileService(new InMemoryProjectRuntimeProfileStore()));
    }

    private static AgentStrategyAdminController controller(
            String token,
            boolean agentRuntimeEnabled,
            ObjectProvider<ProjectRuntimeProfileUploadService> uploadProvider,
            ProjectRuntimeProfileService runtimeProfileService
    ) {
        InMemoryAgentExecutionProfileStore profileStore = new InMemoryAgentExecutionProfileStore();
        AgentStrategyProfileService strategyService = new AgentStrategyProfileService(
                new InMemoryAgentStrategyProfileStore(),
                new AgentExecutionProfileService(profileStore)
        );
        return new AgentStrategyAdminController(
                strategyService,
                new AgentRuntimeMutationAccessPolicy(token),
                uploadProvider,
                runtimeProfileService,
                agentRuntimeEnabled,
                "rd-bot/pi-agent:local",
                "rd-bot/pi-agent-qa:local",
                "rd-bot/claude-code:local"
        );
    }

    private static AgentStrategyProfile complete(String id) {
        return new AgentStrategyProfile(
                id,
                "ignored",
                "生产",
                true,
                1L,
                List.of(
                        slot("REQUIREMENT_REVIEWER", AgentRuntimeType.CLAUDE_CODE, "deepseek"),
                        slot("SOLUTION_ARCHITECT", AgentRuntimeType.PI, "deepseek"),
                        slot("CODING_AGENT", AgentRuntimeType.PI, "deepseek"),
                        slot("QA_AGENT", AgentRuntimeType.PI, "deepseek")
                )
        );
    }

    private static AgentStrategyRoleSlot slot(String role, AgentRuntimeType runtimeType, String providerId) {
        return new AgentStrategyRoleSlot(
                role,
                runtimeType,
                providerId,
                "",
                "",
                0L,
                "legacy-host-bound",
                1L,
                AgentStrategyImageMode.LOCAL_DEFAULT,
                "",
                "",
                "",
                "",
                ""
        );
    }

    private static ObjectProvider<ProjectRuntimeProfileUploadService> providerOf(
            ProjectRuntimeProfileUploadService uploadService
    ) {
        return new ObjectProvider<>() {
            @Override
            public ProjectRuntimeProfileUploadService getObject() {
                return uploadService;
            }

            @Override
            public ProjectRuntimeProfileUploadService getIfAvailable() {
                return uploadService;
            }

            @Override
            public ProjectRuntimeProfileUploadService getIfUnique() {
                return uploadService;
            }
        };
    }

    private static ObjectProvider<ProjectRuntimeProfileUploadService> emptyUploadProvider() {
        return new ObjectProvider<>() {
            @Override
            public ProjectRuntimeProfileUploadService getObject() {
                return null;
            }

            @Override
            public ProjectRuntimeProfileUploadService getIfAvailable() {
                return null;
            }

            @Override
            public ProjectRuntimeProfileUploadService getIfUnique() {
                return null;
            }
        };
    }
}
