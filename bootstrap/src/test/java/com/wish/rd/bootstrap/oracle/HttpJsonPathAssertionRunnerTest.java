package com.wish.rd.bootstrap.oracle;

import com.wish.rd.bootstrap.oracle.impl.HttpJsonPathAssertionRunner;
import com.wish.rd.engine.oracle.model.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.model.AssertionOutcome;
import com.wish.rd.engine.oracle.model.AssertionResult;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpJsonPathAssertionRunnerTest {

    @TempDir
    Path workspace;

    @Test
    void shouldPassWhenJsonPathValueMatches() {
        AtomicReference<URI> seenUri = new AtomicReference<>();
        HttpJsonPathAssertionRunner runner = new HttpJsonPathAssertionRunner((uri, timeout) -> {
            seenUri.set(uri);
            return """
                    {"data":{"status":"ready"}}
                    """;
        });

        AssertionResult result = runner.run(
                jsonSpec("json-1", "GET /api/health", "$.data.status", "\"ready\""),
                context("http://127.0.0.1:18080")
        );

        assertEquals(AssertionOutcome.PASSED, result.outcome());
        assertEquals(URI.create("http://127.0.0.1:18080/api/health"), seenUri.get());
    }

    @Test
    void shouldFailWhenJsonPathExpectedValueDoesNotMatch() {
        HttpJsonPathAssertionRunner runner = new HttpJsonPathAssertionRunner((uri, timeout) ->
                "{\"data\":{\"status\":\"failed\"}}"
        );

        AssertionResult result = runner.run(
                jsonSpec("json-2", "GET /api/health", "$.data.status", "\"ready\""),
                context("http://127.0.0.1:18080")
        );

        assertEquals(AssertionOutcome.FAILED, result.outcome());
        assertTrue(result.message().contains("$.data.status"));
        assertTrue(result.message().contains("ready"));
    }

    @Test
    void shouldRejectJsonPathRequestOutsideEvaluationBaseHost() {
        HttpJsonPathAssertionRunner runner = new HttpJsonPathAssertionRunner((uri, timeout) ->
                "{\"ok\":true}"
        );

        AssertionResult result = runner.run(
                jsonSpec("json-3", "GET https://evil.example/steal", "$.ok", "true"),
                context("http://127.0.0.1:18080")
        );

        assertEquals(AssertionOutcome.ERROR, result.outcome());
    }

    private AssertionEvaluationContext context(String baseUrl) {
        return new AssertionEvaluationContext(workspace, baseUrl, Map.of());
    }

    private static AssertionSpec jsonSpec(
            String id,
            String action,
            String jsonPath,
            String expected
    ) {
        return new AssertionSpec(
                id,
                "criteria-" + id,
                List.of(),
                "",
                action,
                AssertionType.HTTP_JSONPATH,
                jsonPath,
                "eq",
                expected,
                "",
                List.of(),
                1_000L,
                ""
        );
    }
}
