package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.impl.InMemoryModelProviderProfileStore;
import com.wish.rd.rag.project.agent.model.ModelProviderProfile;
import com.wish.rd.rag.project.agent.model.ModelProviderProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelProviderProfileServiceTest {

    @Test
    void storesOnlyAReferenceToTheCredentialEnvironmentVariable() {
        ModelProviderProfileService service = new ModelProviderProfileService(
                new InMemoryModelProviderProfileStore()
        );

        ModelProviderProfile profile = service.register(new ModelProviderProfile(
                "long-cat",
                "Long Cat",
                ModelProviderProtocol.ANTHROPIC_COMPATIBLE,
                "https://api.example.test/anthropic",
                "LongCat-2.0",
                "LONGCAT_API_KEY",
                true,
                true,
                1L
        ));

        assertEquals("LONGCAT_API_KEY", profile.credentialEnvironmentVariable());
        assertEquals("", profile.credentialValue());
    }

    @Test
    void rejectsAnEnvironmentVariableNameThatCouldContainAValue() {
        ModelProviderProfileService service = new ModelProviderProfileService(
                new InMemoryModelProviderProfileStore()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.register(new ModelProviderProfile(
                        "provider-1",
                        "Provider",
                        ModelProviderProtocol.OPENAI_RESPONSES,
                        "https://api.example.test/v1",
                        "model-1",
                        "secret-value",
                        false,
                        true,
                        1L
                ))
        );
    }
}
