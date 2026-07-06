package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.executor.impl.EngineRequirementExecutorAdapter;
import com.wish.rd.bootstrap.executor.impl.EngineRequirementPullRequestPublisherAdapter;

import com.wish.rd.bootstrap.threading.RdBotThreadPoolConfiguration;
import com.wish.rd.engine.requirement.RequirementExecutorPort;
import com.wish.rd.engine.requirement.RequirementPullRequestPublisherPort;
import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;

/**
 * Spring wiring for the engine-facing requirement executor bridge.
 */
@Configuration(proxyBeanMethods = false)
public class EngineRequirementExecutorConfiguration {

    /**
     * 创建需求执行器桥接端口。
     *
     * @param repairExecutorProvider 修复执行端口
     * @return 需求执行端口
     */
    @Bean
    @ConditionalOnMissingBean(RequirementExecutorPort.class)
    public RequirementExecutorPort requirementExecutor(
            ObjectProvider<RepairExecutorPort> repairExecutorProvider,
            ObjectProvider<CodePlatformPort> codePlatformProvider,
            @Qualifier(RdBotThreadPoolConfiguration.EXECUTOR_IO_EXECUTOR_BEAN)
            AsyncTaskExecutor executorIoTaskExecutor
    ) {
        RepairExecutorPort repairExecutor = repairExecutorProvider.getIfAvailable();
        if (repairExecutor == null) {
            return RequirementExecutorPort.unavailable();
        }
        return new EngineRequirementExecutorAdapter(repairExecutor, executorIoTaskExecutor);
    }

    /**
     * 创建复核后的需求交付 PR 发布端口。
     *
     * @param codePlatformProvider 代码平台端口
     * @return PR 发布端口
     */
    @Bean
    @ConditionalOnMissingBean(RequirementPullRequestPublisherPort.class)
    public RequirementPullRequestPublisherPort requirementPullRequestPublisher(
            ObjectProvider<CodePlatformPort> codePlatformProvider
    ) {
        CodePlatformPort codePlatform = codePlatformProvider.getIfAvailable();
        if (codePlatform == null) {
            return RequirementPullRequestPublisherPort.unavailable();
        }
        return new EngineRequirementPullRequestPublisherAdapter(codePlatform);
    }
}
