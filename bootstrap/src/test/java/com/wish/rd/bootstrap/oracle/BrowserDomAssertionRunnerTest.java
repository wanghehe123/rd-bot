package com.wish.rd.bootstrap.oracle;

import com.wish.rd.bootstrap.oracle.impl.BrowserDomAssertionRunner;
import com.wish.rd.engine.oracle.model.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.model.AssertionOutcome;
import com.wish.rd.engine.oracle.model.AssertionResult;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionType;
import com.wish.rd.exec.repair.oracle.model.HostVerifierWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BrowserDomAssertionRunnerTest {

    @TempDir
    Path workspace;

    @Test
    void shouldEvaluateDomAriaAndRouteAgainstHostBrowserProbe() {
        HostBrowserProbe probe = (selector, context) -> new HostBrowserProbe.BrowserAssertionSnapshot(
                true,
                true,
                "/orders/42",
                Map.of("aria-label", "Save order")
        );
        BrowserDomAssertionRunner runner = new BrowserDomAssertionRunner(probe);
        AssertionEvaluationContext context = new AssertionEvaluationContext(workspace, "http://127.0.0.1:3000", Map.of());

        assertEquals(AssertionOutcome.PASSED, runner.run(
                spec("dom", AssertionType.BROWSER_DOM, "[data-testid='save']", "exists", "", ""), context
        ).outcome());
        assertEquals(AssertionOutcome.PASSED, runner.run(
                spec("aria", AssertionType.BROWSER_ARIA, "[data-testid='save']", "eq", "Save order", "aria-label"), context
        ).outcome());
        assertEquals(AssertionOutcome.PASSED, runner.run(
                spec("route", AssertionType.BROWSER_ROUTE, "browser", "eq", "/orders/42", ""), context
        ).outcome());
    }

    @Test
    void shouldFailClosedWhenNoHostBrowserProbeIsConfigured() {
        BrowserDomAssertionRunner runner = new BrowserDomAssertionRunner();

        AssertionResult result = runner.run(
                spec("dom", AssertionType.BROWSER_DOM, "#save", "exists", "", ""),
                AssertionEvaluationContext.of(workspace)
        );

        assertEquals(AssertionOutcome.ERROR, result.outcome());
    }

    @Test
    void shouldPassHostDerivedCleanRuntimeBaseUrlToBrowserProbe() throws Exception {
        AtomicReference<AssertionEvaluationContext> seenContext = new AtomicReference<>();
        HostBrowserProbe probe = (selector, context) -> {
            seenContext.set(context);
            return new HostBrowserProbe.BrowserAssertionSnapshot(true, true, "/", Map.of());
        };
        BrowserDomAssertionRunner runner = new BrowserDomAssertionRunner(probe);
        try (HostVerifierWorkspace verifierWorkspace = new HostVerifierWorkspace(
                workspace,
                "http://127.0.0.1:38433",
                Map.of("hostVerifierRuntime", "clean-replay-process")
        )) {
            AssertionResult result = runner.run(
                    spec("dom", AssertionType.BROWSER_DOM, "#save", "exists", "", ""),
                    new AssertionEvaluationContext(
                            verifierWorkspace.workspaceRoot(),
                            verifierWorkspace.baseUrl(),
                            verifierWorkspace.attributes()
                    )
            );

            assertEquals(AssertionOutcome.PASSED, result.outcome());
        }

        assertEquals("http://127.0.0.1:38433", seenContext.get().baseUrl());
        assertEquals("clean-replay-process", seenContext.get().attributes().get("hostVerifierRuntime"));
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
                1_000L,
                ""
        );
    }
}
