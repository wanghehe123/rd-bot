package com.wish.rd.bootstrap.observability;

import com.wish.rd.engine.admin.observability.DeliverySloAlertSinkPort;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilitySettings;
import com.wish.rd.engine.admin.observability.model.DeliverySloSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires delivery observability settings. The query service and HTTP adapter are annotated components.
 */
@Configuration
@EnableConfigurationProperties(DeliveryObservabilityProperties.class)
public class DeliveryObservabilityConfiguration {

    /**
     * @param properties bound configuration
     * @return engine settings
     */
    @Bean
    public DeliveryObservabilitySettings deliveryObservabilitySettings(
            DeliveryObservabilityProperties properties
    ) {
        return properties == null
                ? DeliveryObservabilitySettings.defaults()
                : properties.toSettings();
    }

    /**
     * @param properties bound configuration
     * @return observation-only SLO settings
     */
    @Bean
    public DeliverySloSettings deliverySloSettings(DeliveryObservabilityProperties properties) {
        return properties == null
                ? DeliverySloSettings.observationOnlyDefaults()
                : properties.toSloSettings();
    }

    /**
     * Notifications stay off until an explicit approval wires a real sink.
     *
     * @return no-op sink
     */
    @Bean
    public DeliverySloAlertSinkPort deliverySloAlertSinkPort() {
        return DeliverySloAlertSinkPort.noop();
    }
}
