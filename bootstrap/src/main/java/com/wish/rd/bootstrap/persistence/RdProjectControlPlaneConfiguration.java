package com.wish.rd.bootstrap.persistence;

import com.wish.rd.rag.project.alert.RdProjectAlertConfigService;
import com.wish.rd.rag.project.alert.RdProjectAlertConfigStore;
import com.wish.rd.rag.project.budget.RdProjectTokenBudgetService;
import com.wish.rd.rag.project.budget.RdProjectTokenBudgetStore;
import com.wish.rd.rag.project.template.RdProjectTaskTemplateService;
import com.wish.rd.rag.project.template.RdProjectTaskTemplateStore;
import com.wish.rd.rag.project.RdProjectService;
import com.wish.rd.rag.qa.QaValidationProfileService;
import com.wish.rd.rag.qa.QaValidationProfileStore;
import com.wish.rd.rag.project.runtime.ProjectRuntimeProfileService;
import com.wish.rd.rag.project.runtime.ProjectRuntimeProfileStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** PostgreSQL-backed project alert and task-template use cases. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class RdProjectControlPlaneConfiguration {
    @Bean
    RdProjectAlertConfigService rdProjectAlertConfigService(
            RdProjectAlertConfigStore store,
            RdProjectService projectService
    ) {
        return new RdProjectAlertConfigService(store, projectService::get);
    }

    @Bean
    RdProjectTokenBudgetService rdProjectTokenBudgetService(
            RdProjectTokenBudgetStore store,
            RdProjectService projectService
    ) {
        return new RdProjectTokenBudgetService(store, projectService::get);
    }

    @Bean
    RdProjectTaskTemplateService rdProjectTaskTemplateService(
            RdProjectTaskTemplateStore store,
            RdProjectService projectService
    ) {
        return new RdProjectTaskTemplateService(store, projectService::get);
    }

    @Bean
    QaValidationProfileService qaValidationProfileService(QaValidationProfileStore store) {
        return new QaValidationProfileService(store);
    }

    @Bean
    ProjectRuntimeProfileService projectRuntimeProfileService(
            ProjectRuntimeProfileStore store,
            RdProjectService projectService
    ) {
        return new ProjectRuntimeProfileService(store, projectService::get);
    }
}
