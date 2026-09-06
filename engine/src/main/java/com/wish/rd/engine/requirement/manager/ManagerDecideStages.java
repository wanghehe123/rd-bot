package com.wish.rd.engine.requirement.manager;

/** Stage helpers for durable {@code MANAGER_DECIDE:<sourceCommandId>} commands. */
public final class ManagerDecideStages {
    public static final String PREFIX = "MANAGER_DECIDE:";

    private ManagerDecideStages() {
    }

    /**
     * Returns the Manager stage bound to the audited command that just finished.
     *
     * @param sourceCommandId previous command id
     * @return stage string
     */
    public static String forSource(String sourceCommandId) {
        String id = require(sourceCommandId);
        return PREFIX + id;
    }

    /**
     * Returns whether {@code stage} is a parameterized Manager command.
     *
     * @param stage command stage
     * @return true when the prefix is present and a source id follows
     */
    public static boolean isManagerDecide(String stage) {
        String value = stage == null ? "" : stage.strip();
        return value.startsWith(PREFIX) && value.length() > PREFIX.length();
    }

    /**
     * Returns the source command id encoded in the stage.
     *
     * @param stage Manager stage
     * @return source command id
     */
    public static String sourceCommandId(String stage) {
        if (!isManagerDecide(stage)) {
            throw new IllegalArgumentException("not a manager-decide stage: " + stage);
        }
        return stage.strip().substring(PREFIX.length());
    }

    private static String require(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("sourceCommandId is required");
        }
        return normalized;
    }
}
