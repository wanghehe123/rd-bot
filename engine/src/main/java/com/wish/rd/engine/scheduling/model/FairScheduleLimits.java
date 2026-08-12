package com.wish.rd.engine.scheduling.model;

/**
 * Concurrent in-flight and batch limits for fair scheduling.
 *
 * @param maxPerProject   max in-flight commands per project
 * @param maxDocker       global docker token limit
 * @param maxBrowserQa    global browser QA token limit
 * @param maxProvider     global provider token limit
 * @param maxPerProvider  token limit for each individual provider id
 * @param batchSize       max claims per scheduling tick
 * @param agingMillis     age that equals one priority rank of aging boost
 */
public record FairScheduleLimits(
        int maxPerProject,
        int maxDocker,
        int maxBrowserQa,
        int maxProvider,
        int maxPerProvider,
        int batchSize,
        long agingMillis
) {

    public FairScheduleLimits {
        maxPerProject = Math.max(1, maxPerProject);
        maxDocker = Math.max(0, maxDocker);
        maxBrowserQa = Math.max(0, maxBrowserQa);
        maxProvider = Math.max(0, maxProvider);
        maxPerProvider = Math.max(0, maxPerProvider);
        batchSize = Math.max(1, batchSize);
        agingMillis = Math.max(1L, agingMillis);
    }

    /**
     * Backward-compatible limit constructor. Older callers receive the global provider capacity
     * as their per-provider ceiling until production configuration supplies a distinct value.
     */
    public FairScheduleLimits(
            int maxPerProject,
            int maxDocker,
            int maxBrowserQa,
            int maxProvider,
            int batchSize,
            long agingMillis
    ) {
        this(maxPerProject, maxDocker, maxBrowserQa, maxProvider, maxProvider, batchSize, agingMillis);
    }

    public static FairScheduleLimits defaults() {
        return new FairScheduleLimits(2, 4, 2, 8, 2, 8, 60_000L);
    }
}
