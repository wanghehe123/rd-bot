package com.wish.rd.bootstrap.oracle;

import com.wish.rd.engine.oracle.HostAssertionOracle;
import com.wish.rd.engine.oracle.impl.FileAssertionRunner;
import com.wish.rd.engine.oracle.model.AssertionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostOwnedAssertionGateTest {

    @TempDir
    Path workspace;

    private final HostOwnedAssertionGate gate = new HostOwnedAssertionGate(
            new HostAssertionOracle(Map.of(
                    AssertionType.FILE_EXISTS, new FileAssertionRunner(),
                    AssertionType.FILE_FORBIDDEN, new FileAssertionRunner()
            ))
    );

    @Test
    void shouldSkipWhenBundleAbsent() {
        assertTrue(gate.validate("{\"status\":\"PASSED\"}", workspace).isEmpty());
    }

    @Test
    void shouldRejectAgentSuppliedBundleThroughLegacyWorkspaceOverload() {
        List<String> errors = gate.validate("""
                {
                  "hostAssertionBundle": {
                    "contentHash": "sha256:%s",
                    "specs": [{
                      "id": "a1",
                      "sourceCriteriaId": "c1",
                      "assertionType": "FILE_EXISTS",
                      "target": "a.txt",
                      "operator": "exists",
                      "timeoutMillis": 1000
                    }]
                  }
                }
                """.formatted("0".repeat(64)), workspace);
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("hostAssertionBundle is not accepted"));
    }
}
