package com.wish.rd.bootstrap.controller.admin.intent;

import com.wish.rd.engine.admin.intent.IntentTreeAdminEngine;
import com.wish.rd.rag.intent.IntentNodeCommand;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 意图树管理 REST 控制器。
 *
 * <p>对外暴露 /intent-tree 系列接口，提供意图树整体查询、节点增删改，
 * 以及批量启用/禁用/删除。HTTP 适配层，业务编排下沉到 {@link IntentTreeAdminEngine}。
 */
@RestController
public class IntentTreeController {

    private final IntentTreeAdminEngine adminEngine;

    public IntentTreeController(IntentTreeAdminEngine adminEngine) {
        this.adminEngine = adminEngine;
    }

    @GetMapping("/intent-tree/trees")
    public Object tree() {
        return adminEngine.tree();
    }

    @PostMapping("/intent-tree")
    public Object create(@RequestBody IntentNodeCommand command) {
        return adminEngine.create(command);
    }

    @PutMapping("/intent-tree/{id}")
    public Object update(
            @PathVariable("id") String id,
            @RequestBody IntentNodeCommand command
    ) {
        return adminEngine.update(id, command);
    }

    @DeleteMapping("/intent-tree/{id}")
    public Object delete(@PathVariable("id") String id) {
        adminEngine.delete(id);
        return Map.of("deleted", true);
    }

    @PostMapping("/intent-tree/batch/enable")
    public Object batchEnable(@RequestBody IntentNodeBatchRequest request) {
        adminEngine.batchEnable(request.ids());
        return Map.of("enabled", true);
    }

    @PostMapping("/intent-tree/batch/disable")
    public Object batchDisable(@RequestBody IntentNodeBatchRequest request) {
        adminEngine.batchDisable(request.ids());
        return Map.of("disabled", true);
    }

    @PostMapping("/intent-tree/batch/delete")
    public Object batchDelete(@RequestBody IntentNodeBatchRequest request) {
        adminEngine.batchDelete(request.ids());
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

    public record IntentNodeBatchRequest(List<String> ids) {
    }
}
