package com.wish.rd.bootstrap.executor;

import com.wish.rd.rag.project.agent.ModelProviderCredentialService;
import com.wish.rd.rag.project.agent.impl.InMemoryModelProviderCredentialStore;
import com.wish.rd.rag.project.agent.impl.InMemoryModelProviderProfileStore;
import com.wish.rd.rag.project.agent.model.ModelProviderProfile;
import com.wish.rd.rag.project.agent.model.ModelProviderProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StoredThenSystemAuthEnvironmentResolverTest {

    @Test
    void prefersStoredSecretOverProcessEnvironment() {
        InMemoryModelProviderProfileStore profiles = new InMemoryModelProviderProfileStore();
        profiles.save(new ModelProviderProfile(
                "opencode-go", "OpenCode Go", ModelProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                "https://opencode.ai/zen/go/v1", "m", "OPENCODE_API_KEY", false, true, 1L
        ));
        ModelProviderCredentialService credentials = new ModelProviderCredentialService(
                new InMemoryModelProviderCredentialStore(), profiles
        );
        credentials.put("opencode-go", "from-ui");
        var resolver = new StoredThenSystemAuthEnvironmentResolver(
                credentials, env -> "from-process"
        );
        assertEquals("from-ui", resolver.resolve("OPENCODE_API_KEY"));
    }

    @Test
    void fallsBackToDelegateWhenNothingStored() {
        var resolver = new StoredThenSystemAuthEnvironmentResolver(
                new ModelProviderCredentialService(
                        new InMemoryModelProviderCredentialStore(),
                        new InMemoryModelProviderProfileStore()
                ),
                env -> "from-process"
        );
        assertEquals("from-process", resolver.resolve("OPENCODE_API_KEY"));
    }
}
