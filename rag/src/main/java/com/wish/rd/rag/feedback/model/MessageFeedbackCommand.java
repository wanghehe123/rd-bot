package com.wish.rd.rag.feedback.model;

public record MessageFeedbackCommand(
        Integer vote,
        String reason,
        String comment
) {
}
