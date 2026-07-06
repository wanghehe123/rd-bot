package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.executor.impl.EngineBugFixExecutorAdapter;

import com.wish.rd.bootstrap.threading.RdBotThreadPoolConfiguration;
import com.wish.rd.engine.bugfix.BugFixExecutor;
import com.wish.rd.engine.audit.impl.NoopRepairAuditSink;
import com.wish.rd.engine.audit.RepairAuditSinkPort;
import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;

/**
 * Spring wiring for the engine bug-fix executor bridge.
 */
@Configuration(proxyBeanMethods = false)
public class EngineBugFixExecutorConfiguration {

    /**
     * Creates the engine-facing bug-fix executor when execution and code-platform ports are available.
     *
     * @param repairExecutorProvider repair execution port provider
     * @param codePlatformProvider   code-platform port provider
     * @param auditSinkProvider      repair audit sink provider
     * @param repoOwner        repository owner, defaults to a local mock value
     * @param repoName         repository name, defaults to a local mock value
     * @param repositoryUrl    repository clone URL
     * @param baseBranch       base branch, defaults to {@code main}
     * @param workBranchPrefix work branch prefix
     * @return engine bug-fix executor bridge
     */
    @Bean
    @ConditionalOnMissingBean(BugFixExecutor.class)
    public BugFixExecutor bugFixExecutor(
            ObjectProvider<RepairExecutorPort> repairExecutorProvider,
            ObjectProvider<CodePlatformPort> codePlatformProvider,
            ObjectProvider<RepairAuditSinkPort> auditSinkProvider,
            @Value("${rd.executor.repository.owner:local}") String repoOwner,
            @Value("${rd.executor.repository.name:repository}") String repoName,
            @Value("${rd.executor.repository.url:}") String repositoryUrl,
            @Value("${rd.executor.repository.base-branch:main}") String baseBranch,
            @Value("${rd.executor.repository.work-branch-prefix:repair/}") String workBranchPrefix,
            @Qualifier(RdBotThreadPoolConfiguration.EXECUTOR_IO_EXECUTOR_BEAN)
            AsyncTaskExecutor executorIoTaskExecutor
    ) {
        RepairExecutorPort repairExecutor = repairExecutorProvider.getIfAvailable();
        CodePlatformPort codePlatform = codePlatformProvider.getIfAvailable();
        if (repairExecutor == null || codePlatform == null) {
            return BugFixExecutor.mock();
        }
        return new EngineBugFixExecutorAdapter(
                repairExecutor,
                codePlatform,
                new EngineBugFixExecutorAdapter.RepositoryConfig(
                        repoOwner,
                        repoName,
                        repositoryUrl,
                        baseBranch,
                        workBranchPrefix
                ),
                auditSinkProvider.getIfAvailable(NoopRepairAuditSink::instance),
                executorIoTaskExecutor
        );
    }
}
