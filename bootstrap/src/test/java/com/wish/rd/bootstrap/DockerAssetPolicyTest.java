package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerAssetPolicyTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> REQUIRED_RESULT_FIELDS = Set.of(
            "status",
            "summary",
            "changedFiles",
            "testCommands",
            "testStatus",
            "riskLevel",
            "needHumanAction"
    );

    @Test
    void dockerImageAssetsShouldExist() {
        assertTrue(Files.isRegularFile(asset("Dockerfile")));
        assertTrue(Files.isRegularFile(asset("Dockerfile.qa")));
        assertTrue(Files.isRegularFile(asset("rd-qa-evidence.mjs")));
        assertTrue(Files.isRegularFile(asset("playwright-cli.config.json")));
        assertTrue(Files.isRegularFile(asset("package.json")));
        assertTrue(Files.isRegularFile(asset("package-lock.json")));
        assertTrue(Files.isRegularFile(sharedAsset("result.schema.json")));
    }

    @Test
    void qaDockerfileDefaultBrowserCacheMustNotReferenceItselfOrPrivateRegistry() throws IOException {
        String dockerfile = readAsset("Dockerfile.qa");

        // 空机器首次构建合同：browser-cache 阶段默认回落到 Pi 基础镜像（其中 /ms-playwright
        // 为空目录），默认路径必须真实执行 `playwright install chromium`；镜像缓存只能通过
        // 显式 --build-arg BROWSER_CACHE_IMAGE=<旧 QA 镜像> 启用，禁止默认自引用。
        assertFalse(dockerfile.contains("BROWSER_CACHE_IMAGE=rd-bot/pi-agent-qa:local"),
                "QA image must not default to its own tag as browser cache");
        assertTrue(dockerfile.contains("ARG BROWSER_CACHE_IMAGE=${PI_BASE_IMAGE}"),
                "browser-cache stage must default to the Pi base image");
        assertTrue(dockerfile.contains("FROM ${BROWSER_CACHE_IMAGE} AS browser-cache"));
        assertFalse(dockerfile.contains("ghcr.io/"), "no private registry defaults");
        assertFalse(dockerfile.contains("registry.cn-"), "no private registry defaults");

        // 与其配套：Pi 基础镜像必须预先创建空的 /ms-playwright，COPY --from 才能在无缓存时成功。
        String baseDockerfile = readAsset("Dockerfile");
        assertTrue(Pattern.compile("mkdir -p[^\\n]*/ms-playwright").matcher(baseDockerfile).find(),
                "Pi base image must provide an empty /ms-playwright for the QA copy source");
        assertTrue(baseDockerfile.contains("chown -R node:node /opt/rd-pi-bridge /work /ms-playwright"));
    }

    @Test
    void qaDockerfileShouldPinPlaywrightCliAndInstallChromium() throws IOException {
        String dockerfile = readAsset("Dockerfile.qa");

        assertTrue(dockerfile.contains("ARG PLAYWRIGHT_CLI_VERSION=0.1.17"));
        assertTrue(dockerfile.contains("@playwright/cli@${PLAYWRIGHT_CLI_VERSION}"));
        assertTrue(dockerfile.contains("PLAYWRIGHT_BROWSERS_PATH=/ms-playwright"));
        assertTrue(dockerfile.contains("apt-get install -y --no-install-recommends curl zip"));
        assertTrue(dockerfile.contains("install-deps chromium"));
        assertTrue(dockerfile.contains("PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT=120000"));
        assertTrue(dockerfile.contains("for attempt in 1 2 3"));
        assertTrue(dockerfile.contains("install chromium && break"));
        assertTrue(dockerfile.contains("COPY rd-qa-evidence.mjs /usr/local/bin/rd-qa-evidence.mjs"));
        assertTrue(dockerfile.contains("COPY playwright-cli.config.json /etc/rd-bot/playwright-cli.config.json"));
        assertTrue(dockerfile.contains("PLAYWRIGHT_MCP_CONFIG=/etc/rd-bot/playwright-cli.config.json"));
        assertTrue(dockerfile.contains("chown -R node:node /ms-playwright /home/node"));
        assertTrue(dockerfile.contains("USER node"));
        assertTrue(dockerfile.contains("ENTRYPOINT [\"node\", \"/opt/rd-pi-bridge/src/rd-pi-bridge.mjs\"]"));
        assertFalse(containsCredentialCopy(dockerfile));
    }

    @Test
    void qaPlaywrightConfigShouldUseBundledHeadlessChromium() throws IOException {
        JsonNode config = OBJECT_MAPPER.readTree(readAsset("playwright-cli.config.json"));

        assertEquals("chromium", config.path("browser").path("browserName").asText());
        assertTrue(config.path("browser").path("isolated").asBoolean());
        assertTrue(config.path("browser").path("launchOptions").path("headless").asBoolean());
        assertEquals("", config.path("browser").path("launchOptions").path("channel").asText());
        assertTrue(config.path("browser").path("launchOptions").path("chromiumSandbox").isBoolean());
        assertFalse(config.path("browser").path("launchOptions").path("chromiumSandbox").asBoolean());
        assertEquals("stdout", config.path("outputMode").asText());
    }

    @Test
    void dockerfileShouldUseNonRootUserAndPinnedBridgeDependencies() throws IOException {
        String dockerfile = readAsset("Dockerfile");

        assertFalse(dockerfile.startsWith("# syntax=docker/dockerfile:"));
        assertTrue(dockerfile.contains("ARG NODE_BASE_IMAGE=node:22.19.0-bookworm-slim"));
        assertTrue(dockerfile.contains("FROM ${NODE_BASE_IMAGE}"));
        assertTrue(dockerfile.contains("apt-get install"));
        assertTrue(dockerfile.contains("git"));
        assertTrue(dockerfile.contains("ca-certificates"));
        assertTrue(dockerfile.contains("jq"));
        assertTrue(dockerfile.contains("python3"));
        assertTrue(dockerfile.contains("python3-venv"));
        assertFalse(dockerfile.contains("sudo"));
        assertTrue(dockerfile.contains("npm ci --omit=dev"));
        assertTrue(dockerfile.contains("COPY src ./src"));
        assertTrue(dockerfile.contains("COPY protocol ./protocol"));
        assertTrue(dockerfile.contains("/work/input /work/output /work/cache"));
        assertTrue(dockerfile.contains("chown -R node:node /opt/rd-pi-bridge /work"));
        assertTrue(dockerfile.contains("USER node"));
        assertTrue(dockerfile.contains("ENTRYPOINT [\"node\", \"/opt/rd-pi-bridge/src/rd-pi-bridge.mjs\"]"));
        assertFalse(containsCredentialCopy(dockerfile));
    }

    @Test
    void resultSchemaShouldMatchRepairExecutionProtocol() throws IOException {
        JsonNode schema = OBJECT_MAPPER.readTree(
                Files.readString(sharedAsset("result.schema.json"), StandardCharsets.UTF_8)
        );

        assertEquals("object", schema.path("type").asText());
        Set<String> required = OBJECT_MAPPER.convertValue(
                schema.path("required"),
                OBJECT_MAPPER.getTypeFactory().constructCollectionType(Set.class, String.class)
        );
        assertEquals(REQUIRED_RESULT_FIELDS, required);
        assertEquals("string", schema.path("properties").path("summary").path("type").asText());
        assertEquals("array", schema.path("properties").path("changedFiles").path("type").asText());
        assertEquals("array", schema.path("properties").path("testCommands").path("type").asText());
        assertEquals("string", schema.path("properties").path("prBody").path("type").asText());
        assertEquals("boolean", schema.path("properties").path("needHumanAction").path("type").asText());
        assertEquals("\\S", schema.path("properties").path("summary").path("pattern").asText());
        assertEquals("\\S", schema.path("properties").path("changedFiles").path("items").path("pattern").asText());
        assertEquals("\\S", schema.path("properties").path("testCommands").path("items").path("pattern").asText());
        assertTrue(schema.toString().contains("\"prBody\":{\"type\":\"string\",\"pattern\":\"\\\\S\"}"));
        assertTrue(schema.toString().contains("\"not\":{\"properties\":{\"status\":{\"enum\":[\"NEED_INFO\",\"FAILED\"]}"));
        assertTrue(schema.toString().contains("\"SUCCESS\""));
        assertTrue(schema.toString().contains("\"FAILED\""));
        assertTrue(schema.toString().contains("\"NEED_INFO\""));
        assertTrue(schema.toString().contains("\"UNSAFE\""));
        assertTrue(schema.toString().contains("\"SKIPPED\""));
        assertTrue(schema.toString().contains("\"minItems\":1"));
        assertTrue(schema.toString().contains("\"PASSED\""));
        assertTrue(schema.toString().contains("\"SKIPPED\""));
        assertTrue(schema.toString().contains("\"LOW\""));
        assertTrue(schema.toString().contains("\"HIGH\""));
    }

    private static boolean containsCredentialCopy(String dockerfile) {
        Pattern credentialPattern = Pattern.compile(
                "(?i)\\bCOPY\\b.*(\\.ssh|id_rsa|id_ed25519|\\.aws|\\.config/gcloud|credentials|token|secret)"
        );
        return credentialPattern.matcher(dockerfile).find();
    }

    private static String readAsset(String fileName) throws IOException {
        return Files.readString(asset(fileName), StandardCharsets.UTF_8);
    }

    private static Path asset(String fileName) {
        return moduleRoot().resolve("src/main/resources/executor/pi").resolve(fileName);
    }

    /** Assets shared by every execution runtime rather than owned by one of them. */
    private static Path sharedAsset(String fileName) {
        return moduleRoot().resolve("src/main/resources/executor").resolve(fileName);
    }

    private static Path moduleRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (current.getFileName() != null && "bootstrap".equals(current.getFileName().toString())) {
            return current;
        }
        return current.resolve("bootstrap");
    }
}
