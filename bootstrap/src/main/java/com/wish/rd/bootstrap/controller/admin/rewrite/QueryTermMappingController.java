package com.wish.rd.bootstrap.controller.admin.rewrite;

import com.wish.rd.engine.admin.rewrite.QueryTermMappingAdminEngine;
import com.wish.rd.rag.rewrite.QueryTermMappingCommand;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 查询术语映射管理 REST 控制器。
 *
 * <p>对外暴露 /mappings 系列接口，提供查询术语映射的列表、详情、新增、更新、删除。
 * HTTP 适配层，业务编排下沉到 {@link QueryTermMappingAdminEngine}。
 */
@RestController
public class QueryTermMappingController {

    private final QueryTermMappingAdminEngine adminEngine;

    public QueryTermMappingController(QueryTermMappingAdminEngine adminEngine) {
        this.adminEngine = adminEngine;
    }

    @GetMapping("/mappings")
    public Object list() {
        return adminEngine.list();
    }

    @GetMapping("/mappings/{id}")
    public ResponseEntity<Object> get(@PathVariable("id") String id) {
        try {
            return ResponseEntity.ok(adminEngine.get(id));
        } catch (NoSuchElementException exception) {
            return error(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @PostMapping("/mappings")
    public Object create(@RequestBody QueryTermMappingCommand command) {
        return adminEngine.create(command);
    }

    @PutMapping("/mappings/{id}")
    public ResponseEntity<Object> update(
            @PathVariable("id") String id,
            @RequestBody QueryTermMappingCommand command
    ) {
        try {
            return ResponseEntity.ok(adminEngine.update(id, command));
        } catch (NoSuchElementException exception) {
            return error(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @DeleteMapping("/mappings/{id}")
    public ResponseEntity<Object> delete(@PathVariable("id") String id) {
        try {
            adminEngine.delete(id);
            return ResponseEntity.ok(Map.of("deleted", true));
        } catch (NoSuchElementException exception) {
            return error(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    private ResponseEntity<Object> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }
}
