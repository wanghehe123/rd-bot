package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.impl.InMemoryModelProviderCredentialStore;
import com.wish.rd.rag.project.agent.impl.InMemoryModelProviderProfileStore;
import com.wish.rd.rag.project.agent.model.ModelProviderProfile;
import com.wish.rd.rag.project.agent.model.ModelProviderProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelProviderCredentialServiceTest {

    @Test
    void resolvesNewestSecretByEnvironmentVariableWithoutExposingItOnTheProfile() {
        InMemoryModelProviderProfileStore profiles = new InMemoryModelProviderProfileStore();
        profiles.save(new ModelProviderProfile(
                "opencode-go", "OpenCode Go", ModelProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                "https://opencode.ai/zen/go/v1", "deepseek-v4-flash",
                "OPENCODE_API_KEY", false, true, 1L
        ));
        profiles.save(new ModelProviderProfile(
                "opencode-legacy", "OpenCode Legacy", ModelProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                "https://opencode.ai/zen/go/v1", "deepseek-v4-flash",
                "OPENCODE_API_KEY", false, true, 1L
        ));
        ModelProviderCredentialService service = new ModelProviderCredentialService(
                new InMemoryModelProviderCredentialStore(), profiles
        );

        service.put("opencode-legacy", "sk-older");
        service.put("opencode-go", "sk-live-from-ui");

        assertEquals("sk-live-from-ui", service.resolveByEnvironmentVariable("OPENCODE_API_KEY"));
        assertTrue(service.status("opencode-go").configured());
        assertEquals("", profiles.find("opencode-go").orElseThrow().credentialValue());
        assertFalse(service.status("opencode-go").toString().contains("sk-live"));
    }

    @Test
    void blankApiKeyClearsTheStoredSecret() {
        InMemoryModelProviderProfileStore profiles = new InMemoryModelProviderProfileStore();
        profiles.save(new ModelProviderProfile(
                "opencode-go", "OpenCode Go", ModelProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                "https://opencode.ai/zen/go/v1", "deepseek-v4-flash",
                "OPENCODE_API_KEY", false, true, 1L
        ));
        ModelProviderCredentialService service = new ModelProviderCredentialService(
                new InMemoryModelProviderCredentialStore(), profiles
        );
        service.put("opencode-go", "sk-live-from-ui");
        service.put("opencode-go", "  ");
        assertEquals("", service.resolveByEnvironmentVariable("OPENCODE_API_KEY"));
        assertFalse(service.status("opencode-go").configured());
    }

    @Test
    void unknownProviderIsRejected() {
        ModelProviderCredentialService service = new ModelProviderCredentialService(
                new InMemoryModelProviderCredentialStore(),
                new InMemoryModelProviderProfileStore()
        );
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.put("missing", "sk-x")
        );
    }
}
