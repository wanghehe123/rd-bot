package com.wish.rd.bootstrap.threading;

import com.wish.rd.engine.requirement.audit.AuditedWritebackGateMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementDeliveryAuditedWritebackPropertiesTest {

    @Test
    void defaultsToEnforceWhenUnbound() {
        RequirementDeliveryAuditedWritebackProperties properties =
                new RequirementDeliveryAuditedWritebackProperties();
        assertEquals(AuditedWritebackGateMode.ENFORCE, properties.getGateMode());
    }

    @Test
    void bindsShadowOverride() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "rd.requirement-delivery.audited-writeback.gate-mode", "SHADOW"
        ));
        RequirementDeliveryAuditedWritebackProperties properties = new Binder(source)
                .bind("rd.requirement-delivery.audited-writeback",
                        Bindable.of(RequirementDeliveryAuditedWritebackProperties.class))
                .get();
        assertEquals(AuditedWritebackGateMode.SHADOW, properties.getGateMode());
    }

    @Test
    void rejectsUnknownGateModeFailClosed() {
        RequirementDeliveryAuditedWritebackProperties properties =
                new RequirementDeliveryAuditedWritebackProperties();
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> properties.setGateMode("PERMISSIVE"));
        assertTrue(failure.getMessage().contains("ENFORCE") || failure.getMessage().contains("SHADOW"));
    }

    @Test
    void applicationYamlDefaultIsEnforce() throws Exception {
        assertEquals("ENFORCE", gateModeDefault(readYaml(existingPath(
                Path.of("bootstrap/src/main/resources/application.yaml"),
                Path.of("src/main/resources/application.yaml")))));
    }

    @Test
    void cloudExampleUsesShadow() throws Exception {
        assertEquals("SHADOW", gateModeValue(readYaml(existingPath(
                Path.of("deploy/cloud-server/application-local.example.yaml"),
                Path.of("../deploy/cloud-server/application-local.example.yaml")))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readYaml(Path path) throws Exception {
        return new Yaml().load(Files.newInputStream(path));
    }

    @SuppressWarnings("unchecked")
    private static String gateModeDefault(Map<String, Object> root) {
        String raw = gateModeValue(root);
        int defaultStart = raw.lastIndexOf(':');
        int defaultEnd = raw.lastIndexOf('}');
        if (raw.contains("${") && defaultStart > 0 && defaultEnd > defaultStart) {
            return raw.substring(defaultStart + 1, defaultEnd);
        }
        return raw;
    }

    @SuppressWarnings("unchecked")
    private static String gateModeValue(Map<String, Object> root) {
        Map<String, Object> rd = (Map<String, Object>) root.get("rd");
        Map<String, Object> delivery = (Map<String, Object>) rd.get("requirement-delivery");
        Map<String, Object> writeback = (Map<String, Object>) delivery.get("audited-writeback");
        return String.valueOf(writeback.get("gate-mode"));
    }

    private static Path existingPath(Path first, Path second) {
        if (Files.isRegularFile(first)) {
            return first;
        }
        if (Files.isRegularFile(second)) {
            return second;
        }
        throw new IllegalStateException("missing yaml at " + first.toAbsolutePath()
                + " or " + second.toAbsolutePath());
    }
}
