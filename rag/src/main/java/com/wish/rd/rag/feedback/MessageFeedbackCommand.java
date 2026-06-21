package com.wish.rd.rag.feedback;

public record MessageFeedbackCommand(
        Integer vote,
        String reason,
        String comment
) {
}
