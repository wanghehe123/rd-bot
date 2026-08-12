package com.wish.rd.engine.oracle;

import com.wish.rd.engine.oracle.impl.InMemoryHostAssertionBundleStore;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionSpecBundle;
import com.wish.rd.engine.oracle.model.AssertionType;
import com.wish.rd.engine.oracle.model.FrozenAssertionBundle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the immutable identity and idempotency contract of frozen Host bundles.
 */
class HostAssertionBundleStoreTest {

    @Test
    void shouldPersistCanonicalBundleOncePerTaskStageAndScope() {
        HostAssertionBundleStore store = new InMemoryHostAssertionBundleStore();
        FrozenAssertionBundle first = bundle("task-1", "stage-1", "CURRENT", "a1", 1L);
        FrozenAssertionBundle replacement = bundle("task-1", "stage-1", "CURRENT", "a2", 2L);

        FrozenAssertionBundle saved = store.saveIfAbsent(first);
        FrozenAssertionBundle replayed = store.saveIfAbsent(replacement);

        assertEquals(first.contentHash(), saved.contentHash());
        assertEquals(first.contentHash(), replayed.contentHash());
        assertEquals(first.contentHash(), store.find("task-1", "stage-1", "CURRENT").orElseThrow().contentHash());
        assertEquals(1, store.listByTaskAndStage("task-1", "stage-1").size());
    }

    @Test
    void shouldRejectUnknownScopeAndBlankHostIdentity() {
        assertThrows(IllegalArgumentException.class, () -> bundle("", "stage-1", "CURRENT", "a1", 1L));
        assertThrows(IllegalArgumentException.class, () -> bundle("task-1", "stage-1", "OTHER", "a1", 1L));
    }

    private static FrozenAssertionBundle bundle(
            String taskId,
            String stageRunId,
            String scope,
            String id,
            long version
    ) {
        AssertionSpec spec = new AssertionSpec(
                id,
                "criteria-" + id,
                List.of(),
                "",
                "",
                AssertionType.FILE_EXISTS,
                "README.md",
                "exists",
                "",
                "",
                List.of(),
                1_000L,
                ""
        );
        return new FrozenAssertionBundle(taskId, stageRunId, scope, AssertionSpecBundle.freeze(List.of(spec)), version);
    }
}
