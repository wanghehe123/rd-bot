package com.wish.rd.bootstrap.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresDeliveryObservabilityRealSmokePreconditionsTest {

    @Test
    void skipIsNotPassAndRequiredPostgresUrlIsListed() {
        assertTrue(PostgresDeliveryObservabilityRealSmokeTest.missingRequiredProperties().contains(
                "rd.delivery-observability.smoke.postgres-url"));
        assertFalse(Boolean.getBoolean("rd.integration.delivery-observability.enabled"),
                "default unit-test JVM must not enable the real smoke");
    }
}
