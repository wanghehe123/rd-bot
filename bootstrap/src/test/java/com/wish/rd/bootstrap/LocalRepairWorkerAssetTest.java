package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the bundled local repair worker can produce deterministic smoke patches.
 */
class LocalRepairWorkerAssetTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void shouldApplyReadmeRequirementSmokePatch() throws IOException, InterruptedException {
        Assumptions.assumeTrue(commandExists("node"), "node is required to run the local repair worker asset");
        Path repo = tempDir.resolve("repo");
        Path input = tempDir.resolve("input");
        Path output = tempDir.resolve("output");
        Files.createDirectories(repo);
        Files.createDirectories(input);
        Files.writeString(repo.resolve("package.json"), """
                {"name":"waimai-delivery-system"}
                """, StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("README.md"), """
                # 外卖平台
                """, StandardCharsets.UTF_8);
        Files.writeString(input.resolve("prompt.md"), """
                # 预期结果
                在 README.md 中新增 RD-Bot 需求交付验收记录。
                要求只修改 README.md，不修改业务代码。
                """, StandardCharsets.UTF_8);

        ProcessBuilder processBuilder = new ProcessBuilder("node", workerScript().toString())
                .directory(tempDir.toFile())
                .redirectErrorStream(true);
        processBuilder.environment().putAll(Map.of(
                "RD_LOCAL_REPAIR_REPO", repo.toString(),
                "PROMPT_FILE", input.resolve("prompt.md").toString(),
                "OUTPUT_DIR", output.toString(),
                "RESULT_FILE", output.resolve("result.json").toString(),
                "PATCH_FILE", output.resolve("patch.diff").toString(),
                "TEST_LOG_FILE", output.resolve("test.log").toString()
        ));
        Process process = processBuilder.start();
        process.getOutputStream().close();
        boolean completed = process.waitFor(10, TimeUnit.SECONDS);

        JsonNode result = OBJECT_MAPPER.readTree(output.resolve("result.json").toFile());
        String readme = Files.readString(repo.resolve("README.md"), StandardCharsets.UTF_8);

        assertTrue(completed);
        assertEquals(0, process.exitValue());
        assertEquals("SUCCESS", result.path("status").asText());
        assertEquals("README.md", result.path("changedFiles").get(0).asText());
        assertTrue(readme.contains("RD-Bot 需求交付验收记录"));
        assertTrue(readme.contains("只修改 README.md"));
    }

    private Path workerScript() {
        Path modulePath = Path.of("src/main/resources/executor/claude/rd-local-repair-worker.mjs")
                .toAbsolutePath()
                .normalize();
        if (Files.isRegularFile(modulePath)) {
            return modulePath;
        }
        return Path.of("bootstrap/src/main/resources/executor/claude/rd-local-repair-worker.mjs")
                .toAbsolutePath()
                .normalize();
    }

    private boolean commandExists(String command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("sh", "-c", "command -v " + command).start();
        return process.waitFor(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS) && process.exitValue() == 0;
    }
}
