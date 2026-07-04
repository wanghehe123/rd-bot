package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionSmokePreconditionsTest {

    @Test
    void shouldFailEnabledSmokeWhenRealProductionPropertiesAreMissing() {
        AssertionError error = assertThrows(
                AssertionError.class,
                () -> ProductionSmokePreconditions.requireReady(
                        "multi-agent",
                        List.of("rd.multi-agent.smoke.production-evidence=true"),
                        Path.of("qa-runs/missing.md")
                )
        );

        assertTrue(error.getMessage().contains("multi-agent production smoke requires real properties"));
        assertTrue(error.getMessage().contains("rd.multi-agent.smoke.production-evidence=true"));
        assertTrue(error.getMessage().contains("qa-runs/missing.md"));
    }

    @Test
    void shouldAllowEnabledSmokeWhenAllRealProductionPropertiesArePresent() {
        assertDoesNotThrow(() -> ProductionSmokePreconditions.requireReady(
                "feishu-alert",
                List.of(),
                Path.of("qa-runs/not-used.md")
        ));
    }
}
