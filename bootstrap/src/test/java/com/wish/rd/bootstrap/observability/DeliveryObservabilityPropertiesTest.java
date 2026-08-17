package com.wish.rd.bootstrap.observability;

import com.wish.rd.engine.admin.observability.model.DeliveryObservabilitySettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryObservabilityPropertiesTest {

    @Test
    void bindsEnvironmentPlaceholdersToSafeDefaults() {
        DeliveryObservabilityProperties properties = new DeliveryObservabilityProperties();
        DeliveryObservabilitySettings settings = properties.toSettings();
        assertEquals(50, settings.maxPageSize());
        assertEquals(30, settings.minP99Samples());
        assertEquals(Duration.ofMinutes(1), settings.staleAfter());
        assertTrue(properties.toSloSettings().observationOnly());
        assertFalse(properties.toSloSettings().notificationsEnabled());
    }

    @Test
    void bindsOverrideValuesFromEnvironmentStyleMap() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "rd.observability.delivery.max-page-size", "25",
                "rd.observability.delivery.min-p99-samples", "12",
                "rd.observability.delivery.snapshot-stale-after", "30s"
        ));
        DeliveryObservabilityProperties properties = new Binder(source)
                .bind("rd.observability.delivery", Bindable.of(DeliveryObservabilityProperties.class))
                .get();
        assertEquals(25, properties.getMaxPageSize());
        assertEquals(12, properties.getMinP99Samples());
        assertEquals(Duration.ofSeconds(30), properties.getSnapshotStaleAfter());
    }

    @Test
    void engineSettingsRejectNonPositivePageSize() {
        assertThrows(IllegalArgumentException.class,
                () -> new DeliveryObservabilitySettings(0, 30, Duration.ofMinutes(1), Duration.ofSeconds(5), 48));
    }
}
