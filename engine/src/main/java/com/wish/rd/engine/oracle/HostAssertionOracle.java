package com.wish.rd.engine.oracle;

import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Host-owned Oracle: verifies assertion bundle integrity then executes registered runners.
 * Missing runners fail closed as {@link AssertionOutcome#UNSUPPORTED}.
 */
public final class HostAssertionOracle {

    private final Map<AssertionType, AssertionRunnerPort> runners;

    public HostAssertionOracle(Map<AssertionType, AssertionRunnerPort> runners) {
        Map<AssertionType, AssertionRunnerPort> copy = new EnumMap<>(AssertionType.class);
        if (runners != null) {
            runners.forEach((type, runner) -> {
                if (type != null && runner != null) {
                    copy.put(type, runner);
                }
            });
        }
        this.runners = Map.copyOf(copy);
    }

    /**
     * Evaluates a frozen bundle. Tampered specs/hash fail before any runner executes.
     *
     * @param bundle  frozen specs
     * @param context evaluation context
     * @return aggregated report
     */
    public AssertionRunReport evaluate(AssertionSpecBundle bundle, AssertionEvaluationContext context) {
        Objects.requireNonNull(bundle, "bundle must not be null");
        Objects.requireNonNull(context, "context must not be null");
        // Construction of AssertionSpecBundle already enforces hash integrity.
        List<AssertionResult> results = new ArrayList<>();
        for (AssertionSpec spec : bundle.specs()) {
            AssertionRunnerPort runner = runners.get(spec.assertionType());
            if (runner == null) {
                results.add(AssertionResult.unsupported(spec.id(), spec.assertionType()));
                continue;
            }
            try {
                AssertionResult result = runner.run(spec, context);
                if (result == null) {
                    results.add(AssertionResult.error(spec.id(), spec.assertionType(), "runner returned null"));
                } else {
                    results.add(result);
                }
            } catch (RuntimeException exception) {
                String message = exception.getMessage() == null || exception.getMessage().isBlank()
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage();
                results.add(AssertionResult.error(spec.id(), spec.assertionType(), message));
            }
        }
        String failure = results.stream()
                .filter(result -> result.outcome() != AssertionOutcome.PASSED)
                .map(result -> result.assertionId() + ":" + result.outcome() + ":" + result.message())
                .findFirst()
                .orElse("");
        boolean passed = failure.isBlank() && !bundle.specs().isEmpty();
        if (bundle.specs().isEmpty()) {
            failure = "assertion bundle has no specs";
            passed = false;
        }
        return new AssertionRunReport(bundle.contentHash(), results, passed, failure);
    }
}
