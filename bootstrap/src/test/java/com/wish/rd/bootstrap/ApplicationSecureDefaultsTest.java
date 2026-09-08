package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 首发个人实验版安全默认值合同（openspec/changes/prepare-personal-open-source-release）。
 * 直接读取原始 YAML 并断言 `${ENV:default}` 占位符的默认段：不启动 Spring 上下文也能钉住
 * 「原生默认 loopback、外部入口默认关闭、token 缺省为空」的边界；Docker 容器层由
 * `deploy/docker/application-docker.yaml` 覆盖，宿主 loopback 发布由 Compose contract 测试钉住。
 */
class ApplicationSecureDefaultsTest {

    private static final Path APP_YAML = Path.of("src/main/resources/application.yaml");
    private static final Path DOCKER_OVERLAY = Path.of("../deploy/docker/application-docker.yaml");

    @Test
    void defaultConfigurationIsLoopbackAndExternalIntakeIsOff() throws IOException {
        Map<String, Object> yaml = load(APP_YAML);

        assertEquals("127.0.0.1", defaultOf(yaml, "server.address"),
                "native server.address must default to loopback");
        assertEquals("false", defaultOf(yaml, "rd.feishu.im.enabled"),
                "Feishu IM intake must be off by default");
        assertEquals("false", defaultOf(yaml, "rd.feishu.im.local-listener.enabled"),
                "Feishu local listener must be off by default");
        assertEquals("false", defaultOf(yaml, "rd.feishu.im.write-back.enabled"),
                "Feishu write-back must be off by default");
        assertEquals("false", defaultOf(yaml, "rd.ticket.write-back.enabled"),
                "ticket write-back must be off by default");
    }

    @Test
    void emptyMutationTokenKeepsRuntimeMutationDenied() throws IOException {
        Map<String, Object> yaml = load(APP_YAML);

        // 空默认 = AgentRuntimeMutationAccessPolicy fail closed（503），不存在公共固定 token。
        assertEquals("", defaultOf(yaml, "rd.executor.agent-runtime.mutation-token"));
        assertEquals("", defaultOf(yaml, "rd.executor.docker.runtime-profiles.upload-token"),
                "upload token must have no public default");
        // 占位符默认段之外的任何非空 token 字面量都不允许出现（禁止 hard-coded token）。
        assertTrue(rawYamlText(APP_YAML).stream().noneMatch(line ->
                        line.matches(".*(mutation-token|upload-token):\\s*[^$\\s].*")),
                "no hard-coded token default may appear in application.yaml");
    }

    @Test
    void dockerOverlayListensOnAllInterfacesInsideTheContainerOnly() throws IOException {
        Map<String, Object> yaml = load(DOCKER_OVERLAY);

        assertEquals("0.0.0.0", defaultOf(yaml, "server.address"),
                "container must listen on all interfaces; host publish stays loopback (compose)");
        assertEquals("false", defaultOf(yaml, "rd.feishu.im.enabled"),
                "overlay must re-disable Feishu intake to resist main-config drift");
        assertEquals("false", defaultOf(yaml, "rd.feishu.im.local-listener.enabled"));
        assertEquals("false", defaultOf(yaml, "rd.feishu.im.write-back.enabled"));
        assertEquals("false", defaultOf(yaml, "rd.ticket.write-back.enabled"));
        assertEquals("", defaultOf(yaml, "rd.executor.agent-runtime.mutation-token"),
                "overlay must not introduce a public mutation token");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(Path path) throws IOException {
        return new Yaml().load(Files.readString(path));
    }

    private static java.util.List<String> rawYamlText(Path path) throws IOException {
        return Files.readAllLines(path);
    }

    /** 读取 `${ENV:default}` 中的 default 段；非占位符值原样返回。 */
    private static String defaultOf(Map<String, Object> yaml, String dottedKey) {
        Object value = yaml;
        for (String part : dottedKey.split("\\.")) {
            assertTrue(value instanceof Map, "missing nested key: " + dottedKey);
            value = ((Map<String, Object>) value).get(part);
        }
        assertTrue(value != null, "missing key: " + dottedKey);
        String raw = String.valueOf(value);
        if (raw.startsWith("${") && raw.endsWith("}")) {
            String inner = raw.substring(2, raw.length() - 1);
            int split = inner.indexOf(':');
            if (split >= 0) {
                return inner.substring(split + 1);
            }
        }
        return raw;
    }
}
