package com.wish.rd.bootstrap.evaluation;

import com.wish.rd.bootstrap.evaluation.impl.FakeCodingBenchmarkExecutionPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the coding benchmark trial executor implementation.
 *
 * <p>{@code rd.evaluation.coding-benchmark.executor=fake} registers the container-free
 * {@link FakeCodingBenchmarkExecutionPort}; any other value (including the missing default) keeps
 * {@code DockerCodingBenchmarkExecutor}, which carries the mirrored condition on its own
 * {@code @Component}. Exactly one
 * {@link com.wish.rd.engine.evaluation.CodingBenchmarkExecutionPort} bean stays in the context.
 */
@Configuration
public class CodingBenchmarkExecutionConfiguration {

    /** @return the local rehearsal executor used when no Docker daemon or model relay is available */
    @Bean
    @ConditionalOnProperty(name = "rd.evaluation.coding-benchmark.executor", havingValue = "fake")
    public FakeCodingBenchmarkExecutionPort fakeCodingBenchmarkExecutionPort() {
        return new FakeCodingBenchmarkExecutionPort();
    }
}
