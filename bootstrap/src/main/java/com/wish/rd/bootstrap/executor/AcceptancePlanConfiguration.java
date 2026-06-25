package com.wish.rd.bootstrap.executor;

import com.wish.rd.engine.bugfix.acceptance.AcceptancePlanGeneratorPort;
import com.wish.rd.engine.bugfix.acceptance.RagEvidenceAcceptancePlanGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 验收计划生成器装配。
 *
 * <p>默认关闭；显式开启 {@code rd.repair.acceptance.enabled=true} 后使用本地 RAG 证据生成器。
 */
@Configuration(proxyBeanMethods = false)
public class AcceptancePlanConfiguration {

    /**
     * 创建本地 RAG 证据验收计划生成器。
     *
     * @return 验收计划生成端口
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "rd.repair.acceptance", name = "enabled", havingValue = "true")
    public AcceptancePlanGeneratorPort acceptancePlanGeneratorPort() {
        return new RagEvidenceAcceptancePlanGenerator();
    }
}
