package com.wish.rd.engine.requirement.answer;

/** Stage helpers for durable {@code USER_ANSWER_RESUME:<sourceCommandId>} commands. */
public final class UserAnswerResumeStages {
    public static final String PREFIX = "USER_ANSWER_RESUME:";

    private UserAnswerResumeStages() {
    }

    /**
     * Returns the resume stage bound to the Manager source that asked for input.
     *
     * @param sourceCommandId Manager source command id
     * @return stage string
     */
    public static String forSource(String sourceCommandId) {
        String id = sourceCommandId == null ? "" : sourceCommandId.strip();
        if (id.isBlank()) {
            throw new IllegalArgumentException("sourceCommandId is required");
        }
        return PREFIX + id;
    }

    /**
     * Returns whether {@code stage} is a parameterized user-answer resume command.
     *
     * @param stage command stage
     * @return true when the prefix is present
     */
    public static boolean isResume(String stage) {
        String value = stage == null ? "" : stage.strip();
        return value.startsWith(PREFIX) && value.length() > PREFIX.length();
    }

    /**
     * Returns the source command id encoded in the stage.
     *
     * @param stage resume stage
     * @return source command id
     */
    public static String sourceCommandId(String stage) {
        if (!isResume(stage)) {
            throw new IllegalArgumentException("not a user-answer-resume stage: " + stage);
        }
        return stage.strip().substring(PREFIX.length());
    }
}
