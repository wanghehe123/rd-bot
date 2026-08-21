package com.wish.rd.bootstrap.controller.admin.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wish.rd.rag.project.agent.ModelProviderCredentialService;
import com.wish.rd.rag.project.agent.ModelProviderProfileService;
import com.wish.rd.rag.project.agent.impl.InMemoryModelProviderCredentialStore;
import com.wish.rd.rag.project.agent.impl.InMemoryModelProviderProfileStore;
import com.wish.rd.rag.project.agent.model.ModelProviderProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelProviderAdminControllerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void metadataPutDoesNotMarkCredentialConfiguredAndOmitsSecrets() {
        ModelProviderAdminController controller = controller("runtime-token");

        ModelProviderAdminController.ModelProviderView view = controller.upsertMetadata(
                "opencode-go",
                "runtime-token",
                metadataBody()
        );

        assertFalse(view.credentialConfigured());
        assertEquals("OPENCODE_API_KEY", view.credentialEnvironmentVariable());
        assertEquals("", view.toString().contains("sk-") ? "leaked" : "");
        assertFalse(OBJECT_MAPPER.valueToTree(view).has("apiKey"));
        assertFalse(OBJECT_MAPPER.valueToTree(controller.list()).toString().contains("sk-live"));
    }

    @Test
    void credentialPutWithTokenMarksConfiguredWithoutReturningTheSecret() {
        ModelProviderAdminController controller = controller("runtime-token");
        controller.upsertMetadata("opencode-go", "runtime-token", metadataBody());

        ModelProviderAdminController.CredentialWriteResult written = controller.putCredential(
                "opencode-go",
                "runtime-token",
                new ModelProviderAdminController.CredentialWriteRequest("sk-live-from-ui")
        );

        assertTrue(written.configured());
        ModelProviderAdminController.ModelProviderView view = controller.list().getFirst();
        assertTrue(view.credentialConfigured());
        assertFalse(OBJECT_MAPPER.valueToTree(view).toString().contains("sk-live"));
        assertFalse(written.toString().contains("sk-live"));
    }

    @Test
    void credentialPutWithWrongTokenDoesNotStoreTheSecret() {
        ModelProviderAdminController controller = controller("runtime-token");
        controller.upsertMetadata("opencode-go", "runtime-token", metadataBody());

        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> controller.putCredential(
                        "opencode-go",
                        "wrong-token",
                        new ModelProviderAdminController.CredentialWriteRequest("sk-live-from-ui")
                )
        );
        assertEquals(HttpStatus.FORBIDDEN, thrown.getStatusCode());
        assertFalse(controller.list().getFirst().credentialConfigured());
    }

    @Test
    void metadataPutRejectsApiKeyWithoutSavingASecret() throws Exception {
        ModelProviderAdminController controller = controller("runtime-token");
        ObjectNode body = metadataBody();
        body.put("apiKey", "sk-should-not-land");

        assertThrows(IllegalArgumentException.class, () -> controller.upsertMetadata(
                "opencode-go",
                "runtime-token",
                body
        ));
        assertTrue(controller.list().isEmpty());
    }

    private static ModelProviderAdminController controller(String token) {
        InMemoryModelProviderProfileStore profiles = new InMemoryModelProviderProfileStore();
        return new ModelProviderAdminController(
                new ModelProviderProfileService(profiles),
                new ModelProviderCredentialService(new InMemoryModelProviderCredentialStore(), profiles),
                new AgentRuntimeMutationAccessPolicy(token),
                OBJECT_MAPPER
        );
    }

    private static ObjectNode metadataBody() {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("displayName", "OpenCode Go");
        body.put("protocol", ModelProviderProtocol.OPENAI_CHAT_COMPLETIONS.name());
        body.put("baseUrl", "https://opencode.ai/zen/go/v1");
        body.put("modelId", "deepseek-v4-flash");
        body.put("credentialEnvironmentVariable", "OPENCODE_API_KEY");
        body.put("authHeader", false);
        body.put("enabled", true);
        body.put("version", 1L);
        return body;
    }
}
