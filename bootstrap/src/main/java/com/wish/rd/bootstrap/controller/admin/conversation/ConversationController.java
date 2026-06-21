package com.wish.rd.bootstrap.controller.admin.conversation;

import com.wish.rd.engine.admin.conversation.ConversationAdminEngine;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 会话管理 REST 控制器。
 *
 * <p>对外暴露 /conversations 系列接口，提供会话列表、消息历史、重命名、删除。
 * HTTP 适配层，业务编排下沉到 {@link ConversationAdminEngine}。
 */
@RestController
public class ConversationController {

    private static final String DEFAULT_USER_ID = "test-user";

    private final ConversationAdminEngine adminEngine;

    public ConversationController(ConversationAdminEngine adminEngine) {
        this.adminEngine = adminEngine;
    }

    @GetMapping("/conversations")
    public Object list(@RequestParam(value = "userId", required = false) String userId) {
        return adminEngine.list(actualUserId(userId));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public Object messages(
            @PathVariable("conversationId") String conversationId,
            @RequestParam(value = "userId", required = false) String userId
    ) {
        return adminEngine.messages(conversationId, actualUserId(userId));
    }

    @PutMapping("/conversations/{conversationId}")
    public Object rename(
            @PathVariable("conversationId") String conversationId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestBody ConversationRenameRequest request
    ) {
        return adminEngine.rename(conversationId, actualUserId(userId), request.title());
    }

    @DeleteMapping("/conversations/{conversationId}")
    public Object delete(
            @PathVariable("conversationId") String conversationId,
            @RequestParam(value = "userId", required = false) String userId
    ) {
        adminEngine.delete(conversationId, actualUserId(userId));
        return Map.of("deleted", true);
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

    public record ConversationRenameRequest(String title) {
    }
}
