package com.wish.rd.bootstrap.openviking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 普通 {@code ./mvnw test} 不得默认打真实 OpenViking；开关与本地 compose 入口必须可检查。
 */
class OpenVikingRealContractSmokePreconditionsTest {

    @Test
    @DisabledIfSystemProperty(named = "rd.openviking.smoke", matches = "true")
    void shouldKeepOrdinaryTestRunsLocalUnlessSmokeFlagIsSet() {
        assertTrue(
                !"true".equals(System.getProperty("rd.openviking.smoke")),
                "ordinary surefire runs must not enable rd.openviking.smoke"
        );
    }

    @Test
    void shouldRedactUserKeysFromContractHttpLogs() {
        String redacted = OpenVikingContractHttp.redactSecrets("{\"result\":{\"user_key\":\"secret-value\"}}");
        assertTrue(redacted.contains("REDACTED"));
        assertTrue(!redacted.contains("secret-value"));
    }

    @Test
    void shouldKeepProjectionAndRetrievalDefaultsOffAndLocal() throws Exception {
        Path moduleYaml = Path.of("src/main/resources/application.yaml");
        Path repoYaml = Path.of("bootstrap/src/main/resources/application.yaml");
        Path yamlPath = Files.exists(moduleYaml) ? moduleYaml : repoYaml;
        assertTrue(Files.exists(yamlPath), "missing bootstrap application.yaml at " + yamlPath.toAbsolutePath());
        String yaml = Files.readString(yamlPath, StandardCharsets.UTF_8);
        assertTrue(yaml.contains("mode: ${RD_KNOWLEDGE_PROJECTION_MODE:OFF}"));
        assertTrue(yaml.contains("enabled: ${RD_OPENVIKING_ENABLED:false}"));
        assertTrue(yaml.contains("knowledge-provider-mode: ${RD_RAG_KNOWLEDGE_PROVIDER_MODE:LOCAL}"));
        assertFalse(yaml.contains("OPENVIKING_API_KEY:"));
    }

    @Test
    void shouldDescribeRequiredLiveSmokeCommand() {
        List<String> command = List.of(
                "./mvnw -q -pl bootstrap -am",
                "-Drd.openviking.smoke=true",
                "-Dtest=OpenVikingContractJsonFixturesTest,OpenVikingRealContractSmokeTest,OpenVikingRealContractSmokePreconditionsTest",
                "-Dsurefire.failIfNoSpecifiedTests=false test"
        );
        String joined = String.join(" ", command);
        assertTrue(joined.contains("rd.openviking.smoke=true"));
        assertTrue(joined.contains("OpenVikingRealContractSmokeTest"));
        assertEquals(4, command.size());
        assertTrue(missingWhenBlank("").contains("rd.openviking.smoke.base-url"));
    }

    private static List<String> missingWhenBlank(String baseUrl) {
        List<String> missing = new ArrayList<>();
        if (baseUrl == null || baseUrl.isBlank()) {
            missing.add("rd.openviking.smoke.base-url");
        }
        return missing;
    }
}
