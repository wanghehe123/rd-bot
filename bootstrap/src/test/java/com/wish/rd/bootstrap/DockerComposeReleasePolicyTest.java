package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T06：根 docker-compose.yml 的静态合同（行为断言由 scripts/docker/tests/compose_contract_test.sh
 * 对 resolved 配置执行；本测试用纯文本断言钉住 compose 文件本身的关键约束）。
 */
class DockerComposeReleasePolicyTest {

    private static final String COMPOSE = readFileOrSkip();

    private static String readFileOrSkip() {
        try {
            return Files.readString(composePath());
        } catch (IOException e) {
            return "";
        }
    }

    private static Path composePath() {
        Path local = Path.of("docker-compose.yml");
        return Files.exists(local) ? local : Path.of("..", "docker-compose.yml");
    }

    private static void assertCompose(String message, boolean condition) {
        org.junit.jupiter.api.Assumptions.assumeTrue(!COMPOSE.isEmpty(), "compose file not on test path");
        assertTrue(condition, message);
    }

    private static void assertCompose(String message, boolean condition, java.util.function.Supplier<String> detail) {
        org.junit.jupiter.api.Assumptions.assumeTrue(!COMPOSE.isEmpty(), "compose file not on test path");
        assertTrue(condition, message + " " + detail.get());
    }

    @Test
    void stackAvoidsContainerNamesAndHostInfrastructurePorts() {
        assertCompose("container_name must not be used (isolated stacks would collide)",
                !COMPOSE.contains("container_name:"));
        // Published ports = short-syntax `- "host:container"` lines on non-comment lines.
        // Allowed: the loopback app mapping, plus mappings inside the debug-ports profile
        // (inactive unless the profile is enabled).
        boolean inDebugProfile = false;
        List<String> bad = new java.util.ArrayList<>();
        for (String raw : COMPOSE.lines().toList()) {
            String line = raw.strip();
            if (line.startsWith("#") || line.isEmpty()) {
                continue;
            }
            if (line.startsWith("postgres-debug:")) {
                inDebugProfile = true;
            } else if (!raw.startsWith(" ") && line.contains(":")) {
                inDebugProfile = false;
            }
            if (!line.matches("- \"[^\"]+:[0-9]+\"$")) {
                continue;
            }
            boolean appMapping = line.contains("127.0.0.1:${RD_BOT_PORT:-18080}:18080");
            if (!appMapping && !inDebugProfile) {
                bad.add(line);
            }
        }
        assertCompose("only the rd-bot app port may be published, on loopback", bad.isEmpty(),
                () -> "published ports: " + bad);
    }

    @Test
    void dockerSocketIsExclusiveToTheAppServiceWithoutPrivilege() {
        long socketLines = COMPOSE.lines()
                .map(String::strip)
                .filter(line -> !line.startsWith("#"))
                .filter(line -> line.contains("/var/run/docker.sock"))
                .count();
        assertCompose("docker socket is mounted exactly once (rd-bot)", socketLines == 1);
        assertCompose("no privileged services",
                !COMPOSE.matches("(?s).*privileged\\s*:\\s*true.*"));
    }

    @Test
    void appWaitsForMigrationsAndBucketInitBeforeServing() {
        assertCompose("rd-bot depends on migrations completing",
                COMPOSE.matches("(?s).*migrations:\\s*\\n\\s*condition: service_completed_successfully.*"));
        assertCompose("rd-bot depends on minio-init completing",
                COMPOSE.matches("(?s).*minio-init:\\s*\\n\\s*condition: service_completed_successfully.*"));
    }

    @Test
    void baseImagesArePinned() {
        List<String> imageLines = COMPOSE.lines().filter(line -> line.trim().startsWith("image:")).toList();
        for (String line : imageLines) {
            String image = line.replace("image:", "").trim();
            assertFalse(image.endsWith(":latest") || image.equals("redis:7"),
                    "image tags must be immutable: " + image);
        }
        assertCompose("pgvector image pinned", COMPOSE.contains("pgvector/pgvector:pg16"));
        assertCompose("redis image pinned", COMPOSE.contains("redis:7.4-bookworm"));
        assertCompose("minio image pinned to a release", COMPOSE.contains("minio/minio:RELEASE."));
    }

    @Test
    void relayAndWorkspaceWiringUseTheDocumentedContract() {
        assertCompose("relay URL uses the rd-bot egress alias",
                COMPOSE.contains("RD_EXECUTOR_PI_CREDENTIAL_RELAY_URL: http://rd-bot:18080/internal/pi/credential-relay/proxy"));
        assertCompose("relay sidecar network mode comes from the egress variable",
                COMPOSE.contains("RD_EXECUTOR_PI_NETWORK_MODE: ${RD_BOT_EGRESS_NETWORK:-rd-bot-egress}"));
        assertCompose("workspace root is shared at the same absolute path",
                COMPOSE.contains("${RD_BOT_WORKSPACE_ROOT}:${RD_BOT_WORKSPACE_ROOT}"));
    }
}
