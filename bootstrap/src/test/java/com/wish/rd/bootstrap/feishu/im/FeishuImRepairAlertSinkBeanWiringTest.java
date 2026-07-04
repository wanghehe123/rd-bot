package com.wish.rd.bootstrap.feishu.im;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class FeishuImRepairAlertSinkBeanWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    JacksonAutoConfiguration.class
            ))
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void shouldWireFeishuAlertSinkIndependentlyFromTicketProvider() {
        contextRunner
                .withPropertyValues(
                        "rd.repair.ticket.provider=mock",
                        "rd.feishu.im.enabled=true",
                        "rd.feishu.im.app-id=cli-test",
                        "rd.feishu.im.app-secret=secret",
                        "rd.feishu.im.alert.enabled=true",
                        "rd.feishu.im.alert.chat-id=oc-alert"
                )
                .run(context -> assertNotNull(context.getBean(FeishuImRepairAlertSink.class)));
    }

    @Configuration
    @EnableConfigurationProperties(FeishuImProperties.class)
    @Import({FeishuImClient.class, FeishuImRepairAlertSink.class})
    static class TestConfiguration {
    }
}
