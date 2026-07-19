package com.wish.rd.bootstrap.threading;

import com.wish.rd.engine.requirement.job.RequirementDeliveryJobStore;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementDeliveryJobStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies the local durable-job store only when no production adapter is configured. */
@Configuration
public class RequirementDeliveryJobConfiguration {

    @Bean
    @ConditionalOnMissingBean(RequirementDeliveryJobStore.class)
    RequirementDeliveryJobStore inMemoryRequirementDeliveryJobStore() {
        return new InMemoryRequirementDeliveryJobStore();
    }
}
