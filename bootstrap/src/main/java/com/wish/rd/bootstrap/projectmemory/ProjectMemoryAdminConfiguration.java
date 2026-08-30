package com.wish.rd.bootstrap.projectmemory;

import com.wish.rd.engine.admin.projectmemory.FailClosedProjectMemoryMutationAuthorizer;
import com.wish.rd.engine.admin.projectmemory.InMemoryProjectMemoryPurgeConfirmTokenStore;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryGovernanceAuditSink;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryMutationAuthorizer;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryMutationAction;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryPurgeConfirmTokenStore;
import com.wish.rd.engine.admin.projectmemory.TrustedOperatorPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires fail-closed governance authorizer and audit sink for project memory admin mutations. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class ProjectMemoryAdminConfiguration {

    @Bean
    ProjectMemoryMutationAuthorizer projectMemoryMutationAuthorizer(ProjectMemoryGovernanceAuditSink auditSink) {
        return new FailClosedProjectMemoryMutationAuthorizer(auditSink);
    }

    @Bean
    ProjectMemoryGovernanceAuditSink projectMemoryGovernanceAuditSink() {
        return new LoggingProjectMemoryGovernanceAuditSink();
    }

    @Bean
    ProjectMemoryPurgeConfirmTokenStore projectMemoryPurgeConfirmTokenStore() {
        return new InMemoryProjectMemoryPurgeConfirmTokenStore();
    }

    private static final class LoggingProjectMemoryGovernanceAuditSink implements ProjectMemoryGovernanceAuditSink {
        private static final Logger LOGGER = LoggerFactory.getLogger(LoggingProjectMemoryGovernanceAuditSink.class);

        @Override
        public void recordAllowed(
                TrustedOperatorPrincipal principal,
                String projectId,
                ProjectMemoryMutationAction action,
                String requestId,
                String reason
        ) {
            LOGGER.info(
                    "project memory governance allowed operator={} project={} action={} requestId={} reason={}",
                    principal.operatorId(), projectId, action, requestId, reason
            );
        }

        @Override
        public void recordDenied(
                TrustedOperatorPrincipal principal,
                String projectId,
                ProjectMemoryMutationAction action,
                String requestId,
                String reason
        ) {
            LOGGER.warn(
                    "project memory governance denied operator={} project={} action={} requestId={} reason={}",
                    principal == null ? "" : principal.operatorId(), projectId, action, requestId, reason
            );
        }
    }
}
