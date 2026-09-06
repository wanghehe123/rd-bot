package com.wish.rd.bootstrap.executor;

import com.wish.rd.engine.requirement.answer.RequirementUserAnswerTransactionPort;
import com.wish.rd.engine.requirement.answer.impl.InMemoryRequirementUserAnswerTransactionAdapter;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateStore;
import com.wish.rd.engine.requirement.job.RequirementStageCommandStore;
import com.wish.rd.engine.requirement.policy.RequirementPolicyRunStore;
import com.wish.rd.engine.requirement.policy.RequirementPolicyTransactionPort;
import com.wish.rd.engine.requirement.policy.impl.InMemoryRequirementPolicyTransactionAdapter;
import com.wish.rd.engine.requirement.manager.ManagerDecisionStore;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.RdTaskStatusEventStore;
import com.wish.rd.rag.runtime.RdTaskStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 在 {@code rd.knowledge.store=memory} 下装配策略事务端口。
 *
 * <p>该端口依赖任务快照、状态事件与雪花 ID 等运行时 Bean，因此与可独立加载的
 * {@link InMemoryAgentObservabilityConfiguration} 解耦：仅当那些依赖已存在时才注册，
 * 避免破坏"内存模式 publication ledger 无需 Postgres/外部 Bean"的接线不变量。
 * PostgreSQL 模式由 {@code PostgresRequirementPolicyTransactionAdapter} 提供同名端口。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
@ConditionalOnBean({RdTaskStore.class, RdTaskStatusEventStore.class, SnowflakeIdGenerator.class})
public class InMemoryRequirementPolicyTransactionConfiguration {

    /**
     * 提供单运行时策略事务适配器，与内存模式交付链路共用同一批 store。
     *
     * @param policyRuns 策略 ledger 存储
     * @param tasks 任务快照存储
     * @param events 任务状态事件存储
     * @param commands 阶段命令存储
     * @param ids 共享雪花 ID 生成器
     * @param auditedTaskStateStore 审计状态存储（内存模式由观测配置提供）
     * @return 内存模式策略事务端口
     */
    @Bean
    @ConditionalOnMissingBean(RequirementPolicyTransactionPort.class)
    RequirementPolicyTransactionPort requirementPolicyTransactionPort(
            RequirementPolicyRunStore policyRuns,
            RdTaskStore tasks,
            RdTaskStatusEventStore events,
            RequirementStageCommandStore commands,
            SnowflakeIdGenerator ids,
            ObjectProvider<AuditedTaskStateStore> auditedTaskStateStore
    ) {
        return new InMemoryRequirementPolicyTransactionAdapter(
                policyRuns, tasks, events, commands, ids, auditedTaskStateStore.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(RequirementUserAnswerTransactionPort.class)
    RequirementUserAnswerTransactionPort requirementUserAnswerTransactionPort(
            RdTaskStore tasks,
            RdTaskStatusEventStore events,
            RequirementStageCommandStore commands,
            ManagerDecisionStore decisions,
            SnowflakeIdGenerator ids
    ) {
        return new InMemoryRequirementUserAnswerTransactionAdapter(tasks, events, commands, decisions, ids);
    }
}
