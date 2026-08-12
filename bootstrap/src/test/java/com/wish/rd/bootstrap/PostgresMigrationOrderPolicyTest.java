package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresMigrationOrderPolicyTest {

    private static final Pattern VERSION = Pattern.compile("^p(\\d+)_.*\\.sql$");

    @Test
    void piRuntimeSchemaPrecedesDefaultQaPolicyThatReferencesIt() throws Exception {
        Path sqlDirectory = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/resources/sql/postgres");
        List<String> ordered;
        try (var files = Files.list(sqlDirectory)) {
            ordered = files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".sql"))
                    .sorted(Comparator
                            .comparingInt(PostgresMigrationOrderPolicyTest::version)
                            .thenComparing(Comparator.naturalOrder()))
                    .toList();
        }

        assertThat(ordered.indexOf("p8_pi_agent_runtime.sql"))
                .isLessThan(ordered.indexOf("p8_zz_default_qa_v2.sql"));
    }

    @Test
    void retryBindingMigrationRunsAfterItsRoleRetrievalAndAiTargets() throws Exception {
        Path sqlDirectory = Path.of(System.getProperty("user.dir")).resolve("src/main/resources/sql/postgres");
        List<String> ordered;
        try (var files = Files.list(sqlDirectory)) {
            ordered = files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".sql"))
                    .sorted(Comparator.comparingInt(PostgresMigrationOrderPolicyTest::version)
                            .thenComparing(Comparator.naturalOrder())).toList();
        }
        assertThat(ordered.indexOf("p1_multi_agent_orchestration.sql"))
                .isLessThan(ordered.indexOf("p2_rag_retrieval_state.sql"));
        assertThat(ordered.indexOf("p2_rag_retrieval_state.sql"))
                .isLessThan(ordered.indexOf("p3_task_retry_ai_review.sql"));
    }

    private static int version(String fileName) {
        Matcher matcher = VERSION.matcher(fileName);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }
}
