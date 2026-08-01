package com.wish.rd.bootstrap.evaluation;

import com.wish.rd.bootstrap.evaluation.impl.CodingBenchmarkSnapshotToRequestAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Provides non-autowirable beans for the coding benchmark snapshot-to-request adapter.
 */
@Configuration
public class CodingBenchmarkSnapshotAdapterConfig {

    /** @return the root path under which per-trial workspaces are materialised */
    @Bean
    public Path codingBenchmarkOutputRoot(EvaluationProperties properties) {
        return properties.resolvedCodingBenchmarkRoot().resolve("output");
    }

    /** @return a supplier of one-time relay tokens for the model API */
    @Bean
    public Supplier<String> relayTokenSupplier() {
        return () -> "cb-" + UUID.randomUUID().toString().substring(0, 12);
    }

    @Bean
    public CodingBenchmarkSnapshotToRequestAdapter codingBenchmarkSnapshotToRequestAdapter(
            Path codingBenchmarkOutputRoot,
            EvaluationProperties properties,
            Supplier<String> relayTokenSupplier,
            ObjectMapper mapper
    ) {
        return new CodingBenchmarkSnapshotToRequestAdapter(
                codingBenchmarkOutputRoot, properties, relayTokenSupplier, mapper);
    }
}
