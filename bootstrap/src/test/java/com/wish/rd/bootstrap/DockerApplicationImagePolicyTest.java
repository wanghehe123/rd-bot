package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 根 Dockerfile（应用镜像）的静态合同（openspec/changes/prepare-personal-open-source-release T05）：
 * 多阶段、固定基础镜像、非 root、无本地 env/凭据进入镜像层、OCI labels、只读 healthcheck。
 */
class DockerApplicationImagePolicyTest {

    private static final Path ROOT_DOCKERFILE = Path.of("../Dockerfile");
    private static final Path ROOT_DOCKERIGNORE = Path.of("../.dockerignore");

    @Test
    void applicationDockerfileIsMultiStageWithPinnedJava21AndNode22() throws IOException {
        String dockerfile = read(ROOT_DOCKERFILE);

        assertTrue(dockerfile.contains("FROM ${NODE_IMAGE} AS frontend-build"));
        assertTrue(dockerfile.contains("FROM ${MAVEN_IMAGE} AS java-build"));
        assertTrue(dockerfile.contains("FROM ${RUNTIME_IMAGE} AS runtime"));

        assertTrue(dockerfile.contains("ARG NODE_IMAGE=node:22.19.0-bookworm-slim"));
        assertTrue(dockerfile.contains("ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21"));
        assertTrue(dockerfile.contains("ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-"),
                "runtime base must pin a JRE 21 tag (no :latest)");
        assertFalse(dockerfile.contains(":latest"));

        assertTrue(dockerfile.contains("node --experimental-strip-types --test test/*.test.ts"),
                "frontend contract tests must run during image build");
        assertTrue(dockerfile.contains("npm run typecheck"));
        assertTrue(dockerfile.contains("npm run build"));
        assertTrue(dockerfile.contains("./mvnw -B -pl bootstrap -am -DskipTests package"));
    }

    @Test
    void applicationImageRunsAsNonRootWithoutDaemonOrSecrets() throws IOException {
        String dockerfile = read(ROOT_DOCKERFILE);

        assertTrue(dockerfile.contains("useradd --uid 10001"));
        assertTrue(dockerfile.contains("USER rdbot"));
        // Docker daemon 在镜像内启动属于禁区；socket 访问由 Compose group_add 在运行时注入。
        assertFalse(dockerfile.matches("(?s).*dockerd.*"), "no docker daemon inside the image");
        assertFalse(dockerfile.contains("/var/run/docker.sock"),
                "the image must not bake in a socket path reference");

        assertFalse(dockerfile.matches("(?s).*COPY\\s+\\.env.*"), "no env file copies");
        assertFalse(dockerfile.contains("ghcr.io/"), "no private registry defaults");
        assertFalse(dockerfile.matches("(?s).*(API_KEY|PASSWORD|TOKEN)\\s*=\\s*\"?[A-Za-z0-9]{8,}"),
                "no hard-coded secrets in the Dockerfile");
    }

    @Test
    void applicationImageCarriesOciLabelsAndReadOnlyHealthcheck() throws IOException {
        String dockerfile = read(ROOT_DOCKERFILE);

        assertTrue(dockerfile.contains("org.opencontainers.image.licenses=\"MIT\""));
        assertTrue(dockerfile.contains("org.opencontainers.image.revision=\"${VCS_REF}\""));
        assertTrue(dockerfile.contains("org.opencontainers.image.source="));
        assertTrue(dockerfile.contains("HEALTHCHECK"));
        assertTrue(dockerfile.contains("curl -fsS http://127.0.0.1:18080/admin"),
                "healthcheck must be a read-only admin fetch inside the container");
        assertTrue(dockerfile.contains("ENTRYPOINT [\"java\", \"-jar\", \"/app/app.jar\"]"));
    }

    @Test
    void dockerignoreKeepsLocalStateSecretsAndBuildOutputsOutOfTheContext() throws IOException {
        List<String> lines = Files.readAllLines(ROOT_DOCKERIGNORE);
        String joined = String.join("\n", lines);

        for (String required : new String[]{".git/", "target/", "node_modules/", ".rd-bot-data/",
                "qa-runs/", "*.env", "*.local", "docs/"}) {
            assertTrue(joined.contains(required), ".dockerignore must exclude " + required);
        }
        // wrapper/源码/lockfile 不被整仓排除
        assertFalse(joined.matches("(?s).*^mvnw$.*"), "mvnw must stay in the build context");
        assertFalse(joined.matches("(?s).*^frontend/$.*"), "frontend sources must stay in the context");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }
}
