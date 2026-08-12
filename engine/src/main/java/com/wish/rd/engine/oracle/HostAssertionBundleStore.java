package com.wish.rd.engine.oracle;

import com.wish.rd.engine.oracle.model.FrozenAssertionBundle;

import java.util.List;
import java.util.Optional;

/**
 * Host persistence port for immutable assertion bundles keyed by QA stage and scope.
 */
public interface HostAssertionBundleStore {

    /**
     * Stores a bundle only when its task/stage/scope identity has not previously been frozen.
     *
     * @param bundle Host-compiled immutable bundle
     * @return the canonical stored bundle, including an earlier replay's value
     */
    FrozenAssertionBundle saveIfAbsent(FrozenAssertionBundle bundle);

    /**
     * Looks up one frozen scope contract.
     *
     * @param taskId task identity
     * @param stageRunId immutable QA stage identity
     * @param scope QA scope
     * @return frozen bundle when present
     */
    Optional<FrozenAssertionBundle> find(String taskId, String stageRunId, String scope);

    /**
     * Lists every frozen scope bundle for the stage in deterministic scope order.
     *
     * @param taskId task identity
     * @param stageRunId immutable QA stage identity
     * @return frozen bundles
     */
    List<FrozenAssertionBundle> listByTaskAndStage(String taskId, String stageRunId);

    /** Returns a no-op store for compatibility paths where Host assertions are not configured. */
    static HostAssertionBundleStore unavailable() {
        return new HostAssertionBundleStore() {
            @Override
            public FrozenAssertionBundle saveIfAbsent(FrozenAssertionBundle bundle) {
                return bundle;
            }

            @Override
            public Optional<FrozenAssertionBundle> find(String taskId, String stageRunId, String scope) {
                return Optional.empty();
            }

            @Override
            public List<FrozenAssertionBundle> listByTaskAndStage(String taskId, String stageRunId) {
                return List.of();
            }
        };
    }
}
