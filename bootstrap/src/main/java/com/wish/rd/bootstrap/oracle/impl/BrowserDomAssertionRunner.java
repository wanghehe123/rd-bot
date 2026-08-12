package com.wish.rd.bootstrap.oracle.impl;

import com.wish.rd.bootstrap.oracle.HostBrowserProbe;
import com.wish.rd.engine.oracle.AssertionRunnerPort;
import com.wish.rd.engine.oracle.model.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.model.AssertionResult;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionType;

import java.util.List;
import java.util.Objects;

/** Host runner for browser DOM, ARIA, visibility, and route-state assertions. */
public final class BrowserDomAssertionRunner implements AssertionRunnerPort {

    private final HostBrowserProbe probe;

    /** Creates a fail-closed runner until a real Host browser probe is wired. */
    public BrowserDomAssertionRunner() {
        this(HostBrowserProbe.unavailable());
    }

    public BrowserDomAssertionRunner(HostBrowserProbe probe) {
        this.probe = probe == null ? HostBrowserProbe.unavailable() : probe;
    }

    @Override
    public AssertionResult run(AssertionSpec spec, AssertionEvaluationContext context) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(context, "context must not be null");
        AssertionType type = spec.assertionType();
        if (type != AssertionType.BROWSER_DOM
                && type != AssertionType.BROWSER_ARIA
                && type != AssertionType.BROWSER_VISIBLE
                && type != AssertionType.BROWSER_ROUTE) {
            return AssertionResult.unsupported(spec.id(), type);
        }
        HostBrowserProbe.BrowserAssertionSnapshot snapshot;
        try {
            snapshot = probe.inspect(new HostBrowserProbe.BrowserProbeRequest(
                    type,
                    spec.target(),
                    spec.action(),
                    spec.timeoutMillis()
            ), context);
            if (snapshot == null) {
                return AssertionResult.error(spec.id(), type, "Host browser probe returned null");
            }
        } catch (Exception exception) {
            String message = exception.getMessage();
            return AssertionResult.error(spec.id(), type,
                    message == null || message.isBlank() ? exception.getClass().getSimpleName() : message);
        }
        List<String> evidence = List.copyOf(spec.evidenceRequired());
        return switch (type) {
            case BROWSER_DOM -> dom(spec, snapshot, evidence);
            case BROWSER_VISIBLE -> visibility(spec, snapshot, evidence);
            case BROWSER_ARIA -> aria(spec, snapshot, evidence);
            case BROWSER_ROUTE -> route(spec, snapshot, evidence);
            default -> AssertionResult.unsupported(spec.id(), type);
        };
    }

    private static AssertionResult dom(
            AssertionSpec spec,
            HostBrowserProbe.BrowserAssertionSnapshot snapshot,
            List<String> evidence
    ) {
        boolean expectedExists = "exists".equalsIgnoreCase(spec.operator());
        boolean passed = expectedExists == snapshot.exists();
        String message = expectedExists
                ? (snapshot.exists() ? "DOM target exists" : "DOM target is missing")
                : (snapshot.exists() ? "forbidden DOM target exists" : "forbidden DOM target is absent");
        return passed ? AssertionResult.passed(spec.id(), spec.assertionType(), message, evidence)
                : AssertionResult.failed(spec.id(), spec.assertionType(), message, evidence);
    }

    private static AssertionResult visibility(
            AssertionSpec spec,
            HostBrowserProbe.BrowserAssertionSnapshot snapshot,
            List<String> evidence
    ) {
        boolean expectedVisible = "visible".equalsIgnoreCase(spec.operator());
        boolean passed = expectedVisible == snapshot.visible();
        String message = expectedVisible
                ? (snapshot.visible() ? "DOM target is visible" : "DOM target is hidden")
                : (snapshot.visible() ? "DOM target is visible" : "DOM target is hidden as expected");
        return passed ? AssertionResult.passed(spec.id(), spec.assertionType(), message, evidence)
                : AssertionResult.failed(spec.id(), spec.assertionType(), message, evidence);
    }

    private static AssertionResult aria(
            AssertionSpec spec,
            HostBrowserProbe.BrowserAssertionSnapshot snapshot,
            List<String> evidence
    ) {
        String attribute = spec.action().isBlank() ? "aria-label" : spec.action();
        String actual = snapshot.aria(attribute);
        boolean passed = spec.expected().equals(actual);
        String message = passed ? "ARIA attribute matches" : "ARIA attribute does not match";
        return passed ? AssertionResult.passed(spec.id(), spec.assertionType(), message, evidence)
                : AssertionResult.failed(spec.id(), spec.assertionType(), message, evidence);
    }

    private static AssertionResult route(
            AssertionSpec spec,
            HostBrowserProbe.BrowserAssertionSnapshot snapshot,
            List<String> evidence
    ) {
        boolean passed = spec.expected().equals(snapshot.route());
        String message = passed ? "browser route matches" : "browser route does not match";
        return passed ? AssertionResult.passed(spec.id(), spec.assertionType(), message, evidence)
                : AssertionResult.failed(spec.id(), spec.assertionType(), message, evidence);
    }
}
