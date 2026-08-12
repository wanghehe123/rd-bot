package com.wish.rd.bootstrap.oracle;

import com.wish.rd.bootstrap.oracle.impl.BrowserDomAssertionRunner;
import com.wish.rd.bootstrap.oracle.impl.ContainerHostBrowserProbe;
import com.wish.rd.engine.oracle.model.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.model.AssertionOutcome;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionType;
import com.wish.rd.exec.repair.docker.ContainerRunnerPort;
import com.wish.rd.exec.repair.docker.model.ContainerRunRequest;
import com.wish.rd.exec.repair.docker.model.ContainerRunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerHostBrowserProbeTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldEvaluateDomAriaVisibilityAndRouteFromHostContainerSnapshot() {
        List<ContainerRunRequest> requests = new ArrayList<>();
        ContainerHostBrowserProbe probe = probe(requests, """
                {
                  "version": 1,
                  "exists": true,
                  "visible": true,
                  "route": "/orders/42",
                  "ariaAttributes": {"aria-label": "Save order"}
                }
                """);
        BrowserDomAssertionRunner runner = new BrowserDomAssertionRunner(probe);
        AssertionEvaluationContext context = context("http://127.0.0.1:3000");

        assertEquals(AssertionOutcome.PASSED, runner.run(
                spec("dom", AssertionType.BROWSER_DOM, "[data-testid='save']", "exists", "", ""), context
        ).outcome());
        assertEquals(AssertionOutcome.PASSED, runner.run(
                spec("aria", AssertionType.BROWSER_ARIA, "[data-testid='save']", "eq", "Save order", "aria-label"), context
        ).outcome());
        assertEquals(AssertionOutcome.PASSED, runner.run(
                spec("visible", AssertionType.BROWSER_VISIBLE, "[data-testid='save']", "visible", "", ""), context
        ).outcome());
        assertEquals(AssertionOutcome.PASSED, runner.run(
                spec("route", AssertionType.BROWSER_ROUTE, "browser", "eq", "/orders/42", ""), context
        ).outcome());

        assertEquals(4, requests.size());
        ContainerRunRequest request = requests.getFirst();
        assertEquals("rd-bot/pi-agent-qa:test", request.image());
        assertEquals("host-browser-probe", request.command().getFirst());
        assertTrue(request.command().contains("http://127.0.0.1:3000"));
        assertTrue(request.command().stream().noneMatch(value -> value.contains("/bin/sh") || value.contains("bash -c")));
        assertTrue(request.mounts().values().contains("/work/repo:ro"));
        assertTrue(request.securityPolicy().enabled());
        assertTrue(request.executionTimeoutMillis() > 0L);
    }

    @Test
    void shouldCapBrowserTimeoutAndFailClosedWhenContainerTimesOut() {
        List<ContainerRunRequest> requests = new ArrayList<>();
        ContainerHostBrowserProbe probe = new ContainerHostBrowserProbe(
                request -> {
                    requests.add(request);
                    return new ContainerRunResult(124, request.executionTimeoutMillis(), "", "", null, null, null, null, null, Map.of());
                },
                configuration(1_000L, 2_000L, 2_048L)
        );
        BrowserDomAssertionRunner runner = new BrowserDomAssertionRunner(probe);
        AssertionEvaluationContext context = context("http://127.0.0.1:3000");

        var result = runner.run(
                spec("timed-out", AssertionType.BROWSER_DOM, "#save", "exists", "", ""), context
        );

        assertEquals(AssertionOutcome.ERROR, result.outcome());
        assertTrue(result.message().contains("timed out"));
        assertEquals(1, requests.size());
        assertTrue(requests.getFirst().executionTimeoutMillis() <= 3_000L);
        assertTrue(requests.getFirst().command().contains("2000"));
    }

    @Test
    void shouldRejectHostileSelectorAndCredentialBearingBaseUrlBeforeStartingContainer() {
        List<ContainerRunRequest> requests = new ArrayList<>();
        ContainerHostBrowserProbe probe = probe(requests, "{}");
        AssertionEvaluationContext normalContext = context("http://127.0.0.1:3000");

        IllegalArgumentException hostileSelector = assertThrows(IllegalArgumentException.class, () -> probe.inspect(
                new HostBrowserProbe.BrowserProbeRequest(
                        AssertionType.BROWSER_DOM,
                        "javascript:alert(1)",
                        "",
                        1_000L
                ),
                normalContext
        ));
        assertTrue(hostileSelector.getMessage().contains("selector"));

        IllegalArgumentException credentialUrl = assertThrows(IllegalArgumentException.class, () -> probe.inspect(
                new HostBrowserProbe.BrowserProbeRequest(
                        AssertionType.BROWSER_DOM,
                        "#save",
                        "",
                        1_000L
                ),
                context("http://user:password@127.0.0.1:3000")
        ));
        assertTrue(credentialUrl.getMessage().contains("base URL"));
        assertTrue(requests.isEmpty());
    }

    @Test
    void shouldRejectOversizedProbeOutputBeforeParsingIt() {
        ContainerHostBrowserProbe probe = new ContainerHostBrowserProbe(
                request -> {
                    Path result = request.outputDirectory().resolve("result.json");
                    Files.writeString(result, "x".repeat(512));
                    return new ContainerRunResult(0, 1L, "", "", result, null, null, null, null, Map.of());
                },
                configuration(1_000L, 2_000L, 128L)
        );

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> probe.inspect(
                new HostBrowserProbe.BrowserProbeRequest(
                        AssertionType.BROWSER_DOM,
                        "#save",
                        "",
                        1_000L
                ),
                context("http://127.0.0.1:3000")
        ));

        assertTrue(exception.getMessage().contains("output limit"));
    }

    private ContainerHostBrowserProbe probe(List<ContainerRunRequest> requests, String resultJson) {
        ContainerRunnerPort runner = request -> {
            requests.add(request);
            Path result = request.outputDirectory().resolve("result.json");
            Files.writeString(result, resultJson);
            return new ContainerRunResult(0, 1L, "", "", result, null, null, null, null, Map.of());
        };
        return new ContainerHostBrowserProbe(runner, configuration(1_000L, 2_000L, 2_048L));
    }

    private ContainerHostBrowserProbe.Configuration configuration(
            long defaultTimeoutMillis,
            long maximumTimeoutMillis,
            long maximumOutputBytes
    ) {
        return new ContainerHostBrowserProbe.Configuration(
                "rd-bot/pi-agent-qa:test",
                "bridge",
                temporaryDirectory.resolve("probe-output"),
                defaultTimeoutMillis,
                maximumTimeoutMillis,
                256,
                maximumOutputBytes
        );
    }

    private AssertionEvaluationContext context(String baseUrl) {
        Path workspace = temporaryDirectory.resolve("workspace");
        try {
            Files.createDirectories(workspace);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to create test workspace", exception);
        }
        return new AssertionEvaluationContext(workspace, baseUrl, Map.of());
    }

    private static AssertionSpec spec(
            String id,
            AssertionType type,
            String target,
            String operator,
            String expected,
            String action
    ) {
        return new AssertionSpec(
                id,
                "criteria-" + id,
                List.of(),
                "",
                action,
                type,
                target,
                operator,
                expected,
                "",
                List.of(),
                10_000L,
                ""
        );
    }
}
