package com.wish.rd.bootstrap.projectmemory;

import com.wish.rd.engine.admin.projectmemory.impl.FailClosedProjectMemoryMutationAuthorizer;

import com.wish.rd.rag.project.memory.ProjectMemoryStore;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryAdminQueryPort;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryGovernancePort;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryLegacyLinkStore;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryModeStore;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryOperationStore;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryPurgePort;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T09/W1：memory store 模式下的项目记忆端口装配。
 *
 * <p>首发不支持 memory 路径（README/SECURITY 已声明），但管理台 context 必须能启动：
 * 这里用 rag 现成的 InMemory 实现把端口补齐，治理 mutation 仍由
 * {@link com.wish.rd.engine.admin.projectmemory.FailClosedProjectMemoryMutationAuthorizer}
 * fail closed（未配置可信 operator 时一律拒绝）。PostgreSQL 模式继续由
 * {@code persistence.impl.PostgresProjectMemory*} 提供真实实现。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory")
public final class ProjectMemoryMemoryStoreConfiguration {

    @Bean
    InMemoryProjectMemoryStore inMemoryProjectMemoryStore() {
        return new InMemoryProjectMemoryStore();
    }

    @Bean
    InMemoryProjectMemoryModeStore inMemoryProjectMemoryModeStore() {
        return new InMemoryProjectMemoryModeStore();
    }

    @Bean
    InMemoryProjectMemoryOperationStore inMemoryProjectMemoryOperationStore() {
        return new InMemoryProjectMemoryOperationStore();
    }

    @Bean
    InMemoryProjectMemoryLegacyLinkStore inMemoryProjectMemoryLegacyLinkStore() {
        return new InMemoryProjectMemoryLegacyLinkStore();
    }

    @Bean
    InMemoryProjectMemoryGovernancePort inMemoryProjectMemoryGovernancePort(ProjectMemoryStore store) {
        return new InMemoryProjectMemoryGovernancePort(store);
    }

    @Bean
    InMemoryProjectMemoryAdminQueryPort inMemoryProjectMemoryAdminQueryPort(InMemoryProjectMemoryStore store) {
        return new InMemoryProjectMemoryAdminQueryPort(store);
    }

    @Bean
    InMemoryProjectMemoryPurgePort inMemoryProjectMemoryPurgePort(
            InMemoryProjectMemoryStore store,
            InMemoryProjectMemoryAdminQueryPort queryPort
    ) {
        return new InMemoryProjectMemoryPurgePort(store, queryPort);
    }
}
