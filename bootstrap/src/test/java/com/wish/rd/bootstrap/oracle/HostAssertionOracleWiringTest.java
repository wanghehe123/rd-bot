package com.wish.rd.bootstrap.oracle;

import com.wish.rd.bootstrap.oracle.impl.BrowserDomAssertionRunner;
import com.wish.rd.bootstrap.oracle.impl.ContainerHostBrowserProbe;
import com.wish.rd.bootstrap.oracle.impl.HttpJsonPathAssertionRunner;
import com.wish.rd.bootstrap.oracle.impl.HttpStatusAssertionRunner;
import com.wish.rd.bootstrap.oracle.impl.LogPatternAssertionRunner;
import com.wish.rd.bootstrap.oracle.impl.SqlRowExistsAssertionRunner;
import com.wish.rd.bootstrap.oracle.impl.SqlSemanticAssertionRunner;
import com.wish.rd.engine.oracle.HostAssertionBundleStore;
import com.wish.rd.engine.oracle.HostAssertionOracle;
import com.wish.rd.engine.oracle.impl.FileAssertionRunner;
import com.wish.rd.engine.oracle.model.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.model.AssertionOutcome;
import com.wish.rd.engine.oracle.model.AssertionResult;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionSpecBundle;
import com.wish.rd.engine.oracle.model.AssertionType;
import com.wish.rd.exec.repair.docker.ContainerRunnerPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostAssertionOracleWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(HostAssertionOracleConfiguration.class);

    @TempDir
    Path workspace;

    @Test
    void shouldExposeHostOracleWithFileHttpAndSqlRunners() {
        contextRunner.run(context -> {
            assertNotNull(context.getBean(HostAssertionOracle.class));
            assertNotNull(context.getBean(FileAssertionRunner.class));
            assertNotNull(context.getBean(HttpStatusAssertionRunner.class));
            assertNotNull(context.getBean(HttpJsonPathAssertionRunner.class));
            assertNotNull(context.getBean(SqlRowExistsAssertionRunner.class));
            assertNotNull(context.getBean(SqlSemanticAssertionRunner.class));
            assertNotNull(context.getBean(LogPatternAssertionRunner.class));
            assertNotNull(context.getBean(BrowserDomAssertionRunner.class));
            assertNotNull(context.getBean(HostAssertionBundleStore.class));
            assertNotNull(context.getBean(HostOwnedAssertionGate.class));
        });
    }

    @Test
    void shouldWireConcreteHostBrowserProbeWhenContainerRunnerIsAvailable() {
        contextRunner
                .withBean(ContainerRunnerPort.class, () -> request -> null)
                .run(context -> assertInstanceOf(
                        ContainerHostBrowserProbe.class,
                        context.getBean(HostBrowserProbe.class)
                ));
    }

    @Test
    void shouldEvaluateHttpJsonPathThroughWiredOracle() {
        contextRunner
                .withBean(HttpJsonPathAssertionRunner.class,
                        () -> new HttpJsonPathAssertionRunner((uri, timeout) -> "{\"ok\":true}"))
                .run(context -> {
                    AssertionSpecBundle bundle = AssertionSpecBundle.freeze(List.of(
                            new AssertionSpec(
                                    "w-json",
                                    "c-json",
                                    List.of(),
                                    "",
                                    "GET /health",
                                    AssertionType.HTTP_JSONPATH,
                                    "$.ok",
                                    "eq",
                                    "true",
                                    "",
                                    List.of(),
                                    1_000L,
                                    ""
                            )
                    ));

                    assertTrue(context.getBean(HostAssertionOracle.class)
                            .evaluate(
                                    bundle,
                                    new AssertionEvaluationContext(workspace, "http://127.0.0.1:18080", Map.of())
                            )
                            .passed());
                });
    }

    @Test
    void shouldEvaluateFileAssertionThroughWiredOracle() throws Exception {
        Files.writeString(workspace.resolve("ok.txt"), "pass");
        contextRunner.run(context -> {
            HostAssertionOracle oracle = context.getBean(HostAssertionOracle.class);
            AssertionSpecBundle bundle = AssertionSpecBundle.freeze(List.of(
                    new AssertionSpec(
                            "w1",
                            "c1",
                            List.of(),
                            "",
                            "",
                            AssertionType.FILE_EXISTS,
                            "ok.txt",
                            "exists",
                            "",
                            "",
                            List.of(),
                            1_000L,
                            ""
                    )
            ));

            assertTrue(oracle.evaluate(bundle, AssertionEvaluationContext.of(workspace)).passed());
        });
    }

    @Test
    void shouldFailClosedWhenSqlRunnerLacksDataSource() {
        contextRunner.run(context -> {
            HostAssertionOracle oracle = context.getBean(HostAssertionOracle.class);
            AssertionSpecBundle bundle = AssertionSpecBundle.freeze(List.of(
                    new AssertionSpec(
                            "w2",
                            "c2",
                            List.of(),
                            "",
                            "query",
                            AssertionType.SQL_ROW_EXISTS,
                            "SELECT 1",
                            "exists",
                            "true",
                            "",
                            List.of(),
                            1_000L,
                            ""
                    )
            ));

            AssertionResult result = oracle.evaluate(bundle, AssertionEvaluationContext.of(workspace))
                    .results()
                    .getFirst();
            assertEquals(AssertionOutcome.ERROR, result.outcome());
            assertTrue(result.message().contains("DataSource"));
        });
    }
}
