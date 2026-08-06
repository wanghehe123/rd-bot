package com.wish.rd.bootstrap.oracle;

import com.wish.rd.engine.oracle.AssertionSpecBundle;
import com.wish.rd.engine.oracle.AssertionSpecHasher;
import com.wish.rd.engine.oracle.FileAssertionRunner;
import com.wish.rd.engine.oracle.HostAssertionOracle;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
    void shouldRejectTamperedHash() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "x");
        AssertionSpec spec = fileSpec("a1", "a.txt");
        String goodHash = AssertionSpecHasher.hashBundle(List.of(spec));
        String json = """
                {
                  "hostAssertionBundle": {
                    "contentHash": "%s",
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
                """.formatted(goodHash + "dead");

        List<String> errors = gate.validate(json, workspace);
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("integrity"));
    }

    @Test
    void shouldPassWhenFileExistsAndHashMatches() throws Exception {
        Files.writeString(workspace.resolve("ok.txt"), "pass");
        AssertionSpec spec = fileSpec("ok1", "ok.txt");
        AssertionSpecBundle bundle = AssertionSpecBundle.freeze(List.of(spec));
        String json = """
                {
                  "hostAssertionBundle": {
                    "contentHash": "%s",
                    "specs": [{
                      "id": "ok1",
                      "sourceCriteriaId": "criteria-ok1",
                      "assertionType": "FILE_EXISTS",
                      "target": "ok.txt",
                      "operator": "exists",
                      "timeoutMillis": 1000
                    }]
                  }
                }
                """.formatted(bundle.contentHash());

        assertTrue(gate.validate(json, workspace).isEmpty());
    }

    @Test
    void shouldFailWhenRequiredFileMissing() {
        AssertionSpec spec = fileSpec("miss1", "gone.txt");
        String hash = AssertionSpecHasher.hashBundle(List.of(spec));
        String json = """
                {
                  "hostAssertionBundle": {
                    "contentHash": "%s",
                    "specs": [{
                      "id": "miss1",
                      "sourceCriteriaId": "criteria-miss1",
                      "assertionType": "FILE_EXISTS",
                      "target": "gone.txt",
                      "operator": "exists",
                      "timeoutMillis": 1000
                    }]
                  }
                }
                """.formatted(hash);

        List<String> errors = gate.validate(json, workspace);
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("FAILED") || errors.getFirst().contains("missing"));
    }

    private static AssertionSpec fileSpec(String id, String target) {
        return new AssertionSpec(
                id,
                "criteria-" + id,
                List.of(),
                "",
                "",
                AssertionType.FILE_EXISTS,
                target,
                "exists",
                "",
                "",
                List.of(),
                1_000L,
                ""
        );
    }
}
