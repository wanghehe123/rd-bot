package com.wish.rd.bootstrap.verify;

import com.wish.rd.engine.requirement.verify.HostVerificationChangeSetResolver;
import com.wish.rd.engine.requirement.verify.HostVerificationPort;
import com.wish.rd.engine.requirement.verify.HostVerificationStore;
import com.wish.rd.engine.requirement.verify.HostVerificationWorkspaceFactory;
import com.wish.rd.exec.repair.verify.HostVerificationCommandDetector;
import com.wish.rd.exec.repair.verify.HostVerificationCommandRunner;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

/**
 * Optional Spring wiring for {@link HostVerificationExecutorAdapter}.
 *
 * <p>The bean is created only when a {@link HostVerificationStore} and
 * {@link HostVerificationWorkspaceFactory} are already present, so existing
 * context tests without those seams stay unchanged. Orchestrator injection is
 * Task 6.
 */
@Configuration(proxyBeanMethods = false)
public class HostVerificationExecutorConfiguration {

    /**
     * Host verification port backed by the process runner when workspace + store exist.
     *
     * @param store                  persisted runs
     * @param workspaceFactory       prepared checkout
     * @param changeSetResolver      optional change-set; empty means undeterminable
     * @param idGeneratorProvider    Snowflake ids when available
     * @param timeoutSeconds         wall-clock budget
     * @param evidenceRoot           log root; defaults under {@code java.io.tmpdir}
     * @return adapter or no bean when required seams are absent
     */
    @Bean
    @ConditionalOnBean({HostVerificationStore.class, HostVerificationWorkspaceFactory.class})
    @ConditionalOnMissingBean(HostVerificationPort.class)
    HostVerificationPort hostVerificationPort(
            HostVerificationStore store,
            HostVerificationWorkspaceFactory workspaceFactory,
            ObjectProvider<HostVerificationChangeSetResolver> changeSetResolver,
            ObjectProvider<SnowflakeIdGenerator> idGeneratorProvider,
            @Value("${rd.host-verify.timeout-seconds:600}") int timeoutSeconds,
            @Value("${rd.host-verify.evidence-root:}") String evidenceRoot
    ) {
        SnowflakeIdGenerator idGenerator = idGeneratorProvider.getIfAvailable(SnowflakeIdGenerator::defaultGenerator);
        Path root = evidenceRoot == null || evidenceRoot.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "rd-bot", "host-verify")
                : Path.of(evidenceRoot);
        HostVerificationChangeSetResolver resolver = changeSetResolver.getIfAvailable(
                () -> (task, codingStage, workspace) -> List.of()
        );
        HostVerificationCommandRunner runner = new HostVerificationCommandRunner();
        return new HostVerificationExecutorAdapter(
                store,
                new HostVerificationCommandDetector(),
                runner::run,
                workspaceFactory,
                resolver,
                idGenerator::nextId,
                System::currentTimeMillis,
                timeoutSeconds,
                root
        );
    }
}
