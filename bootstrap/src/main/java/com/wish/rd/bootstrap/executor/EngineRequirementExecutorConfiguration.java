package com.wish.rd.bootstrap.executor;

import com.wish.rd.engine.requirement.RequirementExecutorPort;
import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for the engine-facing requirement executor bridge.
 */
@Configuration(proxyBeanMethods = false)
public class EngineRequirementExecutorConfiguration {

    /**
     * 创建需求执行器桥接端口。
     *
     * @param repairExecutorProvider 修复执行端口
     * @param codePlatformProvider   代码平台端口
     * @return 需求执行端口
     */
    @Bean
    @ConditionalOnMissingBean(RequirementExecutorPort.class)
    public RequirementExecutorPort requirementExecutor(
            ObjectProvider<RepairExecutorPort> repairExecutorProvider,
            ObjectProvider<CodePlatformPort> codePlatformProvider
    ) {
        RepairExecutorPort repairExecutor = repairExecutorProvider.getIfAvailable();
        CodePlatformPort codePlatform = codePlatformProvider.getIfAvailable();
        if (repairExecutor == null || codePlatform == null) {
            return RequirementExecutorPort.unavailable();
        }
        return new EngineRequirementExecutorAdapter(repairExecutor, codePlatform);
    }
}
