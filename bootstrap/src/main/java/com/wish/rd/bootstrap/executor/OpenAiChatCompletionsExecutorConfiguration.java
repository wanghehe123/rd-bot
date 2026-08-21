package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.executor.impl.OpenAiChatCompletionsRepairExecutor;
import com.wish.rd.bootstrap.executor.impl.RoleAwareRepairExecutor;

import com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.net.http.HttpClient;

/**
 * Spring wiring for OpenAI-compatible chat completions execution.
 */
@Configuration(proxyBeanMethods = false)
public class OpenAiChatCompletionsExecutorConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "rd.executor.openai-chat", name = "enabled", havingValue = "true")
    public OpenAiChatCompletionsRepairExecutor openAiChatCompletionsRepairExecutor(
            OpenAiChatCompletionsProperties properties,
            ObjectProvider<DockerClaudeCodeExecutor.AuthEnvironmentResolver> authEnvironmentResolverProvider
    ) {
        DockerClaudeCodeExecutor.AuthEnvironmentResolver resolver = authEnvironmentResolverProvider.getIfAvailable(
                DockerClaudeCodeExecutor.AuthEnvironmentResolver::system);
        return new OpenAiChatCompletionsRepairExecutor(
                properties.toExecutorConfiguration(),
                HttpClient.newHttpClient(),
                () -> resolver.resolve(properties.getApiKeyEnv())
        );
    }

    @Bean
    @Primary
    @ConditionalOnBean({DockerClaudeCodeExecutor.class, OpenAiChatCompletionsRepairExecutor.class})
    public RepairExecutorPort roleAwareRepairExecutor(
            DockerClaudeCodeExecutor dockerExecutor,
            OpenAiChatCompletionsRepairExecutor openAiExecutor
    ) {
        return new RoleAwareRepairExecutor(dockerExecutor, openAiExecutor);
    }
}
