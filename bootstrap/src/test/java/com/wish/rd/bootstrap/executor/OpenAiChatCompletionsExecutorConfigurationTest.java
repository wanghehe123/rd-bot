package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiChatCompletionsExecutorConfigurationTest {

    @Test
    void shouldExposePrimaryRoleAwareRepairExecutorWhenOpenAiChatIsEnabled() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(OpenAiExecutorContext.class)
                .withPropertyValues(
                        "rd.executor.docker.enabled=true",
                        "rd.executor.openai-chat.enabled=true",
                        "rd.executor.openai-chat.provider-name=minimax",
                        "rd.executor.openai-chat.base-url=https://api.minimaxi.com/v1",
                        "rd.executor.openai-chat.api-key-env=MINIMAX_API_KEY",
                        "rd.executor.openai-chat.model=MiniMax-M2.7"
                );

        contextRunner.run(context -> {
            assertTrue(context.getBean(RepairExecutorPort.class) instanceof RoleAwareRepairExecutor);
            assertEquals(1, context.getBeansOfType(OpenAiChatCompletionsRepairExecutor.class).size());
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            DockerExecutorProperties.class,
            ProcessContainerRunner.class,
            ProcessGitRepairWorkspaceRepository.class,
            InMemoryRepairAlertSink.class,
            DockerExecutorConfiguration.class,
            OpenAiChatCompletionsProperties.class,
            OpenAiChatCompletionsExecutorConfiguration.class
    })
    static class OpenAiExecutorContext {
    }
}
