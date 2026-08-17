package com.wish.rd.bootstrap.verify;

import com.wish.rd.bootstrap.executor.impl.RoleHandoffAttachmentResolver;
import com.wish.rd.bootstrap.oracle.impl.CleanHostVerifierWorkspaceFactory;
import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.requirement.verify.HostVerificationChangeSetResolver;
import com.wish.rd.engine.requirement.verify.HostVerificationPort;
import com.wish.rd.engine.requirement.verify.HostVerificationStore;
import com.wish.rd.engine.requirement.verify.HostVerificationWorkspaceFactory;
import com.wish.rd.exec.repair.docker.RepairWorkspaceFactory;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort;
import com.wish.rd.exec.repair.oracle.HostVerifierWorkspaceFactory;
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

/**
 * Optional Spring wiring for {@link HostVerificationExecutorAdapter}.
 *
 * <p>{@link HostVerificationWorkspaceFactory} and
 * {@link HostVerificationChangeSetResolver} are registered here so the port
 * is created whenever a {@link HostVerificationStore} exists. Orchestrator
 * injection is Task 6.
 */
@Configuration(proxyBeanMethods = false)
public class HostVerificationExecutorConfiguration {

    /**
     * Production source for the coding stage's verified candidate patch.
     *
     * @param artifacts artifact store when present
     * @param resolver  QA handoff rematerializer when present
     * @return artifact-backed source, or a fail-closed source when deps are missing
     */
    @Bean
    @ConditionalOnMissingBean(HostVerificationPatchSource.class)
    HostVerificationPatchSource hostVerificationPatchSource(
            ObjectProvider<AgentStageArtifactStore> artifacts,
            ObjectProvider<RoleHandoffAttachmentResolver> resolver
    ) {
        AgentStageArtifactStore store = artifacts.getIfAvailable();
        RoleHandoffAttachmentResolver handoffResolver = resolver.getIfAvailable();
        if (store == null || handoffResolver == null) {
            return (task, codingStage) -> {
                throw new IllegalStateException(
                        "coding stage "
                                + (codingStage == null ? "" : codingStage.stageRunId())
                                + " has no verified candidate-patch.diff");
            };
        }
        return new ArtifactHostVerificationPatchSource(store, handoffResolver);
    }

    /**
     * Replays the coding patch through {@link CleanHostVerifierWorkspaceFactory}.
     *
     * @param verifierFactoryProvider existing Host oracle factory when present
     * @param workspaceFactory        local workspace factory used to build a verifier if needed
     * @param repositoryProvider      repository prepare port used to build a verifier if needed
     * @param patchSource             coding candidate patch
     * @return host-verification workspace factory
     */
    @Bean
    @ConditionalOnMissingBean(HostVerificationWorkspaceFactory.class)
    HostVerificationWorkspaceFactory hostVerificationWorkspaceFactory(
            ObjectProvider<HostVerifierWorkspaceFactory> verifierFactoryProvider,
            ObjectProvider<RepairWorkspaceFactory> workspaceFactory,
            ObjectProvider<RepairWorkspaceRepositoryPort> repositoryProvider,
            HostVerificationPatchSource patchSource
    ) {
        HostVerifierWorkspaceFactory verifier = verifierFactoryProvider.getIfAvailable();
        if (verifier == null) {
            RepairWorkspaceFactory workspaces = workspaceFactory.getIfAvailable();
            RepairWorkspaceRepositoryPort repository = repositoryProvider.getIfAvailable();
            verifier = workspaces == null || repository == null
                    ? HostVerifierWorkspaceFactory.unavailable()
                    : new CleanHostVerifierWorkspaceFactory(workspaces, repository);
        }
        return new CleanHostVerificationWorkspaceFactory(verifier, patchSource);
    }

    /**
     * Lists replayed git paths; empty means undeterminable, not docs-only.
     *
     * @return production change-set resolver
     */
    @Bean
    @ConditionalOnMissingBean(HostVerificationChangeSetResolver.class)
    HostVerificationChangeSetResolver hostVerificationChangeSetResolver() {
        return new GitHostVerificationChangeSetResolver();
    }

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
    @ConditionalOnBean(HostVerificationStore.class)
    @ConditionalOnMissingBean(HostVerificationPort.class)
    HostVerificationPort hostVerificationPort(
            HostVerificationStore store,
            HostVerificationWorkspaceFactory workspaceFactory,
            HostVerificationChangeSetResolver changeSetResolver,
            ObjectProvider<SnowflakeIdGenerator> idGeneratorProvider,
            @Value("${rd.host-verify.timeout-seconds:600}") int timeoutSeconds,
            @Value("${rd.host-verify.evidence-root:}") String evidenceRoot
    ) {
        SnowflakeIdGenerator idGenerator = idGeneratorProvider.getIfAvailable(SnowflakeIdGenerator::defaultGenerator);
        Path root = evidenceRoot == null || evidenceRoot.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "rd-bot", "host-verify")
                : Path.of(evidenceRoot);
        HostVerificationCommandRunner runner = new HostVerificationCommandRunner();
        return new HostVerificationExecutorAdapter(
                store,
                new HostVerificationCommandDetector(),
                runner::run,
                workspaceFactory,
                changeSetResolver,
                idGenerator::nextId,
                System::currentTimeMillis,
                timeoutSeconds,
                root
        );
    }
}
