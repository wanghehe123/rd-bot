package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelProviderCredentialSqlPolicyTest {

    @Test
    void shouldKeepProviderSecretsOffTheProfileTable() throws Exception {
        Path sql = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/resources/sql/postgres/p16_model_provider_credentials.sql");
        String content = Files.readString(sql);

        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_model_provider_credentials"));
        assertTrue(content.contains("REFERENCES rd_model_provider_profiles"));
        assertTrue(content.contains("ON DELETE CASCADE"));
        assertTrue(content.contains("chk_rd_model_provider_credentials_secret"));
        String mapper = Files.readString(Path.of(System.getProperty("user.dir"))
                .resolve("src/main/java/com/wish/rd/bootstrap/persistence/mapper/ModelProviderCredentialMapper.java"));
        assertTrue(mapper.contains("ON CONFLICT (provider_id) DO UPDATE"));
        assertTrue(mapper.contains("DELETE FROM rd_model_provider_credentials"));
    }
}
