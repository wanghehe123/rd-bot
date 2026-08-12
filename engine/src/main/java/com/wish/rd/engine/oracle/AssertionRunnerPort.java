package com.wish.rd.engine.oracle;

import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.model.AssertionResult;

/**
 * Executes one Host-owned assertion against a prepared evaluation context.
 * Infrastructure adapters (HTTP/SQL/file) live outside the engine.
 */
@FunctionalInterface
public interface AssertionRunnerPort {

    /**
     * Runs one assertion.
     *
     * @param spec    frozen assertion
     * @param context opaque evaluation context (workspace root, base URL, …)
     * @return result for this assertion
     */
    AssertionResult run(AssertionSpec spec, AssertionEvaluationContext context);
}
