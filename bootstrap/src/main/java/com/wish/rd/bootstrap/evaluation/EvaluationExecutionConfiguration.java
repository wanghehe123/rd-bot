package com.wish.rd.bootstrap.evaluation;

import com.wish.rd.bootstrap.evaluation.impl.LocalEvaluationTaskScheduler;
import com.wish.rd.engine.evaluation.EvaluationRunStore;
import com.wish.rd.engine.evaluation.EvaluationTaskSchedulerPort;
import com.wish.rd.engine.evaluation.impl.InMemoryEvaluationRunStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Infrastructure wiring for local Web evaluation execution and non-PostgreSQL fallback state. */
@Configuration
public class EvaluationExecutionConfiguration {

    /** Provides an explicit memory-mode Store only when PostgreSQL did not register one. */
    @Bean
    @ConditionalOnMissingBean(EvaluationRunStore.class)
    public EvaluationRunStore inMemoryEvaluationRunStore() {
        return new InMemoryEvaluationRunStore();
    }

    /** Creates the bounded local scheduler without publishing a broad ExecutorService bean. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(EvaluationTaskSchedulerPort.class)
    public LocalEvaluationTaskScheduler evaluationTaskSchedulerPort(EvaluationProperties properties) {
        return new LocalEvaluationTaskScheduler(properties.getParallelism());
    }
}
