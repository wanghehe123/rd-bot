package com.wish.rd.engine.oracle;

import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionSpecBundle;
import com.wish.rd.engine.oracle.model.AssertionType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks Host-owned AssertionSpec immutability and content-hash integrity for WP-3.
 */
class AssertionSpecIntegrityTest {

    @Test
    void shouldRejectBlankAssertionIdentity() {
        assertThrows(IllegalArgumentException.class, () -> httpStatus("", "200"));
    }

    @Test
    void shouldChangeHashWhenExpectedValueIsMutated() {
        AssertionSpec original = httpStatus("assert-1", "200");
        AssertionSpec mutated = httpStatus("assert-1", "500");

        assertNotEquals(AssertionSpecHasher.hash(original), AssertionSpecHasher.hash(mutated));
    }

    @Test
    void shouldFreezeBundleAndRejectTamperedSpecsAgainstStoredHash() {
        AssertionSpec first = httpStatus("assert-1", "200");
        AssertionSpec second = jsonPath("assert-2", "$.ok", "true");
        AssertionSpecBundle frozen = AssertionSpecBundle.freeze(List.of(first, second));

        assertTrue(AssertionSpecBundle.matches(List.of(second, first), frozen.contentHash()),
                "bundle hash must be order-independent by assertion id");

        List<AssertionSpec> tampered = new ArrayList<>();
        tampered.add(first);
        tampered.add(httpStatus("assert-2", "false"));
        assertFalse(AssertionSpecBundle.matches(tampered, frozen.contentHash()));
        assertThrows(IllegalArgumentException.class,
                () -> new AssertionSpecBundle(tampered, frozen.contentHash()));
    }

    @Test
    void shouldRejectAgentDeletingAssertionFromFrozenBundle() {
        AssertionSpecBundle frozen = AssertionSpecBundle.freeze(List.of(
                httpStatus("assert-1", "200"),
                jsonPath("assert-2", "$.ok", "true")
        ));

        assertFalse(AssertionSpecBundle.matches(List.of(httpStatus("assert-1", "200")), frozen.contentHash()));
        assertEquals(2, frozen.specs().size());
    }

    private static AssertionSpec httpStatus(String id, String expected) {
        return new AssertionSpec(
                id,
                "criteria-" + id,
                List.of(),
                "",
                "GET /health",
                AssertionType.HTTP_STATUS,
                "/health",
                "eq",
                expected,
                "",
                List.of("qa-evidence/network/"),
                5_000L,
                ""
        );
    }

    private static AssertionSpec jsonPath(String id, String target, String expected) {
        return new AssertionSpec(
                id,
                "criteria-" + id,
                List.of(),
                "",
                "GET /api",
                AssertionType.HTTP_JSONPATH,
                target,
                "eq",
                expected,
                "",
                List.of("qa-evidence/network/"),
                5_000L,
                ""
        );
    }
}
