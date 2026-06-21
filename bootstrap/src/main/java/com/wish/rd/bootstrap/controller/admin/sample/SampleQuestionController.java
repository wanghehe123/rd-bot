package com.wish.rd.bootstrap.controller.admin.sample;

import com.wish.rd.engine.admin.sample.SampleQuestionAdminEngine;
import com.wish.rd.rag.sample.SampleQuestionCommand;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 样例问题管理 REST 控制器。
 *
 * <p>对外暴露 /sample-questions 系列管理接口（分页/详情/增删改），
 * 以及 /rag/sample-questions 用于返回欢迎页样例问题。HTTP 适配层，
 * 业务编排下沉到 {@link SampleQuestionAdminEngine}。
 */
@RestController
public class SampleQuestionController {

    private final SampleQuestionAdminEngine adminEngine;

    public SampleQuestionController(SampleQuestionAdminEngine adminEngine) {
        this.adminEngine = adminEngine;
    }

    @GetMapping("/rag/sample-questions")
    public Object welcomeQuestions() {
        return adminEngine.listWelcomeQuestions();
    }

    @GetMapping("/sample-questions")
    public Object page(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "current", defaultValue = "1") int current,
            @RequestParam(value = "size", defaultValue = "10") int size
    ) {
        return adminEngine.page(keyword, current, size);
    }

    @GetMapping("/sample-questions/{id}")
    public Object get(@PathVariable("id") String id) {
        return adminEngine.get(id);
    }

    @PostMapping("/sample-questions")
    public Object create(@RequestBody SampleQuestionCommand command) {
        return adminEngine.create(command);
    }

    @PutMapping("/sample-questions/{id}")
    public Object update(
            @PathVariable("id") String id,
            @RequestBody SampleQuestionCommand command
    ) {
        return adminEngine.update(id, command);
    }

    @DeleteMapping("/sample-questions/{id}")
    public Object delete(@PathVariable("id") String id) {
        adminEngine.delete(id);
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
}
