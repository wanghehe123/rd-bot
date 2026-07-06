package com.wish.rd.bootstrap.controller.admin.feedback;

import com.wish.rd.engine.admin.feedback.MessageFeedbackAdminEngine;
import com.wish.rd.rag.feedback.model.MessageFeedbackCommand;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 消息反馈 REST 控制器。
 *
 * <p>对外暴露 /conversations/messages/{messageId}/feedback 接口，
 * 用于对消息进行点赞/点踩反馈。HTTP 适配层，业务编排下沉到
 * {@link MessageFeedbackAdminEngine}。
 */
@RestController
public class MessageFeedbackController {

    private static final String DEFAULT_USER_ID = "test-user";

    private final MessageFeedbackAdminEngine feedbackAdminEngine;

    public MessageFeedbackController(MessageFeedbackAdminEngine feedbackAdminEngine) {
        this.feedbackAdminEngine = feedbackAdminEngine;
    }

    @PostMapping("/conversations/messages/{messageId}/feedback")
    public Object submitFeedback(
            @PathVariable("messageId") String messageId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestBody MessageFeedbackRequest request
    ) {
        return feedbackAdminEngine.submit(
                messageId,
                actualUserId(userId),
                new MessageFeedbackCommand(request.vote(), request.reason(), request.comment())
        );
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Object> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", exception.getMessage()));
    }

    private String actualUserId(String userId) {
        return userId == null || userId.isBlank() ? DEFAULT_USER_ID : userId.strip();
    }

    public record MessageFeedbackRequest(Integer vote, String reason, String comment) {
    }
}
