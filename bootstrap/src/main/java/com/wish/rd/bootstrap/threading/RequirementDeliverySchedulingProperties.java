package com.wish.rd.bootstrap.threading;

import com.wish.rd.engine.scheduling.model.FairScheduleLimits;
import com.wish.rd.engine.scheduling.model.RequirementDeliverySchedulingPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds the production fair-scheduling controls for requirement delivery stage commands.
 *
 * <p>The dispatcher converts this mutable Spring binding object into an immutable engine policy
 * once at construction time, so a scheduling tick cannot observe a partially-bound set of limits.
 */
@ConfigurationProperties(prefix = "rd.requirement-delivery.scheduling")
public class RequirementDeliverySchedulingProperties {

    private int maxPerProject = 2;
    private int maxDocker = 4;
    private int maxBrowserQa = 2;
    private int maxProvider = 8;
    private int maxPerProvider = 2;
    private int batchSize = 8;
    private long agingMillis = 60_000L;
    private long commandDeadlineMillis = 60L * 60L * 1000L;
    private String defaultProviderId = "local-provider";
    private Map<String, Integer> projectWeights = new LinkedHashMap<>();

    /** @return maximum simultaneous stage commands for one project */
    public int getMaxPerProject() {
        return maxPerProject;
    }

    /** @param maxPerProject maximum simultaneous stage commands for one project */
    public void setMaxPerProject(int maxPerProject) {
        this.maxPerProject = maxPerProject;
    }

    /** @return global Docker slot limit */
    public int getMaxDocker() {
        return maxDocker;
    }

    /** @param maxDocker global Docker slot limit */
    public void setMaxDocker(int maxDocker) {
        this.maxDocker = maxDocker;
    }

    /** @return global browser QA slot limit */
    public int getMaxBrowserQa() {
        return maxBrowserQa;
    }

    /** @param maxBrowserQa global browser QA slot limit */
    public void setMaxBrowserQa(int maxBrowserQa) {
        this.maxBrowserQa = maxBrowserQa;
    }

    /** @return global provider-call limit */
    public int getMaxProvider() {
        return maxProvider;
    }

    /** @param maxProvider global provider-call limit */
    public void setMaxProvider(int maxProvider) {
        this.maxProvider = maxProvider;
    }

    /** @return maximum simultaneous calls for one provider identity */
    public int getMaxPerProvider() {
        return maxPerProvider;
    }

    /** @param maxPerProvider maximum simultaneous calls for one provider identity */
    public void setMaxPerProvider(int maxPerProvider) {
        this.maxPerProvider = maxPerProvider;
    }

    /** @return maximum commands considered and claimed in one scheduler tick */
    public int getBatchSize() {
        return batchSize;
    }

    /** @param batchSize maximum commands considered and claimed in one scheduler tick */
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    /** @return milliseconds of waiting time that earns one priority rank of aging */
    public long getAgingMillis() {
        return agingMillis;
    }

    /** @param agingMillis milliseconds of waiting time that earns one priority rank of aging */
    public void setAgingMillis(long agingMillis) {
        this.agingMillis = agingMillis;
    }

    /** @return maximum lifetime of a queued stage command */
    public long getCommandDeadlineMillis() {
        return commandDeadlineMillis;
    }

    /** @param commandDeadlineMillis maximum lifetime of a queued stage command */
    public void setCommandDeadlineMillis(long commandDeadlineMillis) {
        this.commandDeadlineMillis = commandDeadlineMillis;
    }

    /** @return fallback provider identity used when no authorized role profile is bound */
    public String getDefaultProviderId() {
        return defaultProviderId;
    }

    /** @param defaultProviderId fallback provider identity used when no role profile is bound */
    public void setDefaultProviderId(String defaultProviderId) {
        this.defaultProviderId = defaultProviderId;
    }

    /** @return configured positive project share weights */
    public Map<String, Integer> getProjectWeights() {
        return Map.copyOf(projectWeights);
    }

    /** @param projectWeights configured positive project share weights */
    public void setProjectWeights(Map<String, Integer> projectWeights) {
        this.projectWeights = projectWeights == null ? new LinkedHashMap<>() : new LinkedHashMap<>(projectWeights);
    }

    /** @return immutable engine policy derived from the currently bound configuration */
    public RequirementDeliverySchedulingPolicy toPolicy() {
        return new RequirementDeliverySchedulingPolicy(
                new FairScheduleLimits(
                        maxPerProject,
                        maxDocker,
                        maxBrowserQa,
                        maxProvider,
                        maxPerProvider,
                        batchSize,
                        agingMillis
                ),
                projectWeights,
                commandDeadlineMillis,
                defaultProviderId
        );
    }
}
