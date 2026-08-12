package com.wish.rd.engine.oracle;

import com.wish.rd.engine.oracle.model.AssertionType;
import com.wish.rd.engine.oracle.model.AssertionSpecBundle;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for compiling only explicit Host-owned assertion definitions.
 */
class AssertionSpecCompilerTest {

    private final AssertionSpecCompiler compiler = new AssertionSpecCompiler();

    @Test
    void shouldCompileStructuredAssertionsIntoSeparateFrozenCurrentAndRegressionBundles() {
        Map<String, AssertionSpecBundle> bundles = compiler.compileByScope("""
                {
                  "assertions": [
                    {
                      "id": "current-file",
                      "scope": "CURRENT",
                      "sourceCriteriaId": "AC-1",
                      "assertionType": "FILE_EXISTS",
                      "target": "src/main/App.java",
                      "operator": "exists"
                    },
                    {
                      "id": "current-json",
                      "scope": "CURRENT",
                      "sourceCriteriaId": "AC-2",
                      "assertionType": "HTTP_JSONPATH",
                      "action": "GET /health",
                      "target": "$.ready",
                      "operator": "eq",
                      "expected": "true"
                    },
                    {
                      "id": "regression-sql",
                      "scope": "REGRESSION",
                      "sourceCriteriaId": "RG-1",
                      "assertionType": "SQL_ROW_EXISTS",
                      "target": "SELECT id FROM public.rd_tasks WHERE id = 1",
                      "operator": "exists"
                    },
                    {
                      "id": "regression-log",
                      "scope": "REGRESSION",
                      "sourceCriteriaId": "RG-2",
                      "assertionType": "LOG_MUST_NOT_MATCH",
                      "target": "qa-evidence/commands/current.log",
                      "operator": "not_matches",
                      "expected": "password\\\\s*="
                    },
                    {
                      "id": "regression-dom",
                      "scope": "REGRESSION",
                      "sourceCriteriaId": "RG-3",
                      "assertionType": "BROWSER_DOM",
                      "target": "[data-testid='save']",
                      "operator": "exists"
                    }
                  ]
                }
                """);

        assertEquals(2, bundles.size());
        assertEquals(2, bundles.get("CURRENT").specs().size());
        assertEquals(3, bundles.get("REGRESSION").specs().size());
        assertEquals(AssertionType.FILE_EXISTS, bundles.get("CURRENT").specs().getFirst().assertionType());
        assertTrue(bundles.get("REGRESSION").contentHash().startsWith("sha256:"));
    }

    @Test
    void shouldRejectUnknownOperatorBeforeFreezingHostBundle() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> compiler.compileByScope("""
                {"assertions":[{
                  "id":"invalid", "scope":"CURRENT", "assertionType":"FILE_EXISTS",
                  "target":"README.md", "operator":"contains"
                }]}
                """));

        assertTrue(exception.getMessage().contains("operator"));
    }

    @Test
    void shouldRejectWriteSqlAndCommentsBeforeFreezingHostBundle() {
        IllegalArgumentException write = assertThrows(IllegalArgumentException.class, () -> compiler.compileByScope("""
                {"assertions":[{
                  "id":"write", "scope":"CURRENT", "assertionType":"SQL_ROW_EXISTS",
                  "target":"UPDATE public.rd_tasks SET status = 'MERGED'", "operator":"exists"
                }]}
                """));
        IllegalArgumentException comment = assertThrows(IllegalArgumentException.class, () -> compiler.compileByScope("""
                {"assertions":[{
                  "id":"comment", "scope":"CURRENT", "assertionType":"SQL_ROW_EXISTS",
                  "target":"SELECT 1 -- harmless comment", "operator":"exists"
                }]}
                """));

        assertTrue(write.getMessage().contains("read-only"));
        assertTrue(comment.getMessage().contains("comment"));
    }

    @Test
    void shouldRejectMalformedInputThatAttemptsToDeclareHostAssertions() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> compiler.compileByScope("{\"assertions\": [")
        );

        assertTrue(exception.getMessage().contains("Host assertion definition JSON"));
    }
}
