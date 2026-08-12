package com.wish.rd.bootstrap.oracle;

import com.wish.rd.engine.oracle.HostAssertionOracle;
import com.wish.rd.bootstrap.oracle.impl.HttpStatusAssertionRunner;
import com.wish.rd.engine.oracle.model.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.model.AssertionOutcome;
import com.wish.rd.engine.oracle.model.AssertionResult;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionSpecBundle;
import com.wish.rd.engine.oracle.model.AssertionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpStatusAssertionRunnerTest {

    @TempDir
    Path workspace;

    @Test
    void shouldPassWhenStatusMatchesExpected() {
        AtomicReference<URI> seen = new AtomicReference<>();
        HttpStatusAssertionRunner runner = new HttpStatusAssertionRunner((uri, timeout) -> {
            seen.set(uri);
            return 200;
        });

        AssertionResult result = runner.run(
                httpSpec("h1", "/health", "200"),
                context("http://127.0.0.1:18080")
        );

        assertEquals(AssertionOutcome.PASSED, result.outcome());
        assertEquals(URI.create("http://127.0.0.1:18080/health"), seen.get());
    }

    @Test
    void shouldFailWhenStatusDoesNotMatch() {
        HttpStatusAssertionRunner runner = new HttpStatusAssertionRunner((uri, timeout) -> 500);

        AssertionResult result = runner.run(
                httpSpec("h2", "/health", "200"),
                context("http://127.0.0.1:18080")
        );

        assertEquals(AssertionOutcome.FAILED, result.outcome());
        assertTrue(result.message().contains("500"));
    }

    @Test
    void shouldErrorWhenBaseUrlMissing() {
        HttpStatusAssertionRunner runner = new HttpStatusAssertionRunner((uri, timeout) -> 200);

        AssertionResult result = runner.run(
                httpSpec("h3", "/health", "200"),
                AssertionEvaluationContext.of(workspace)
        );

        assertEquals(AssertionOutcome.ERROR, result.outcome());
        assertTrue(result.message().toLowerCase().contains("baseurl"));
    }

    @Test
    void shouldRejectAbsoluteTargetOutsideBaseHost() {
        HttpStatusAssertionRunner runner = new HttpStatusAssertionRunner((uri, timeout) -> 200);

        AssertionResult result = runner.run(
                httpSpec("h4", "https://evil.example/steal", "200"),
                context("http://127.0.0.1:18080")
        );

        assertEquals(AssertionOutcome.ERROR, result.outcome());
    }

    @Test
    void shouldIntegrateWithHostOracle() {
        HttpStatusAssertionRunner runner = new HttpStatusAssertionRunner((uri, timeout) -> 204);
        AssertionSpecBundle bundle = AssertionSpecBundle.freeze(List.of(
                httpSpec("h5", "/ready", "204")
        ));
        HostAssertionOracle oracle = new HostAssertionOracle(Map.of(
                AssertionType.HTTP_STATUS, runner
        ));

        assertTrue(oracle.evaluate(bundle, context("http://127.0.0.1:9")).passed());
    }

    @Test
    void shouldHonorTimeoutFromSpec() {
        AtomicReference<Duration> seenTimeout = new AtomicReference<>();
        HttpStatusAssertionRunner runner = new HttpStatusAssertionRunner((uri, timeout) -> {
            seenTimeout.set(timeout);
            return 200;
        });

        runner.run(httpSpec("h6", "/t", "200", 2_500L), context("http://127.0.0.1:18080"));

        assertEquals(Duration.ofMillis(2_500L), seenTimeout.get());
    }

    private AssertionEvaluationContext context(String baseUrl) {
        return new AssertionEvaluationContext(workspace, baseUrl, Map.of());
    }

    private static AssertionSpec httpSpec(String id, String target, String expected) {
        return httpSpec(id, target, expected, 1_000L);
    }

    private static AssertionSpec httpSpec(String id, String target, String expected, long timeoutMillis) {
        return new AssertionSpec(
                id,
                "criteria-" + id,
                List.of(),
                "",
                "GET",
                AssertionType.HTTP_STATUS,
                target,
                "eq",
                expected,
                "",
                List.of(),
                timeoutMillis,
                ""
        );
    }
}
