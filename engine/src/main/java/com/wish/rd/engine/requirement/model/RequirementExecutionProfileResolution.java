package com.wish.rd.engine.requirement.model;

/** Result of resolving the immutable execution profile for one stage attempt. */
public record RequirementExecutionProfileResolution(String snapshotId) {

    public RequirementExecutionProfileResolution {
        snapshotId = snapshotId == null ? "" : snapshotId.strip();
    }

    public boolean resolved() {
        return !snapshotId.isBlank();
    }

    public static RequirementExecutionProfileResolution none() {
        return new RequirementExecutionProfileResolution("");
    }
}
