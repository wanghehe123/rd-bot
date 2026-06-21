package com.wish.rd.rag.sample;

public record SampleQuestionCommand(
        String title,
        String description,
        String question
) {

    public SampleQuestionCommand {
        title = trimToNull(title);
        description = trimToNull(description);
        question = question == null ? null : question.strip();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isBlank() ? null : trimmed;
    }
}
