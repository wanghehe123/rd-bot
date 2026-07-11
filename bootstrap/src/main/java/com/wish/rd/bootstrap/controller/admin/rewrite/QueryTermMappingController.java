package com.wish.rd.bootstrap.controller.admin.rewrite;

import com.wish.rd.engine.admin.rewrite.QueryTermMappingAdminEngine;
import com.wish.rd.rag.rewrite.model.QueryRewritePreview;
import com.wish.rd.rag.rewrite.model.QueryTermMappingCommand;
import com.wish.rd.rag.rewrite.model.QueryTermMappingScope;
import com.wish.rd.rag.project.RdProjectService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 查询术语映射管理 REST 控制器。
 *
 * <p>对外暴露 /mappings 系列接口，提供查询术语映射的列表、详情、新增、更新、删除。
 * HTTP 适配层，业务编排下沉到 {@link QueryTermMappingAdminEngine}。
 */
@RestController
public class QueryTermMappingController {

    private final QueryTermMappingAdminEngine adminEngine;
    private final RdProjectService projectService;

    @Autowired
    public QueryTermMappingController(
            QueryTermMappingAdminEngine adminEngine,
            ObjectProvider<RdProjectService> projectServiceProvider
    ) {
        this.adminEngine = adminEngine;
        this.projectService = projectServiceProvider.getIfAvailable();
    }

    public QueryTermMappingController(QueryTermMappingAdminEngine adminEngine) {
        this.adminEngine = adminEngine;
        this.projectService = null;
    }

    @GetMapping("/mappings")
    public ResponseEntity<Object> list(
            @RequestParam(value = "projectId", required = false) String projectId,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "enabled", required = false) Boolean enabled,
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        try {
            return ResponseEntity.ok(adminEngine.list(projectId, parseScope(scope), enabled, keyword));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/mappings/{id}")
    public ResponseEntity<Object> get(@PathVariable("id") String id) {
        try {
            requireMappingId(id);
            return ResponseEntity.ok(adminEngine.get(id));
        } catch (NoSuchElementException exception) {
            return error(HttpStatus.NOT_FOUND, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/mappings")
    public ResponseEntity<Object> create(@RequestBody QueryTermMappingCommand command) {
        try {
            validateProjectScope(command);
            return ResponseEntity.ok(adminEngine.create(command));
        } catch (NoSuchElementException | DataIntegrityViolationException exception) {
            return error(HttpStatus.NOT_FOUND, projectNotFoundMessage(command));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PutMapping("/mappings/{id}")
    public ResponseEntity<Object> update(
            @PathVariable("id") String id,
            @RequestBody QueryTermMappingCommand command
    ) {
        try {
            requireMappingId(id);
            validateProjectScope(command);
            return ResponseEntity.ok(adminEngine.update(id, command));
        } catch (NoSuchElementException exception) {
            return error(HttpStatus.NOT_FOUND, exception.getMessage());
        } catch (DataIntegrityViolationException exception) {
            return error(HttpStatus.NOT_FOUND, projectNotFoundMessage(command));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @DeleteMapping("/mappings/{id}")
    public ResponseEntity<Object> delete(@PathVariable("id") String id) {
        try {
            requireMappingId(id);
            adminEngine.delete(id);
            return ResponseEntity.ok(Map.of("deleted", true));
        } catch (NoSuchElementException exception) {
            return error(HttpStatus.NOT_FOUND, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/mappings/preview")
    public ResponseEntity<Object> preview(@RequestBody PreviewRequest request) {
        try {
            PreviewRequest safeRequest = request == null ? new PreviewRequest("", "") : request;
            QueryRewritePreview preview = adminEngine.preview(safeRequest.projectId(), safeRequest.text());
            return ResponseEntity.ok(preview);
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    private QueryTermMappingScope parseScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        try {
            return QueryTermMappingScope.valueOf(scope.strip().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("scope must be GLOBAL or PROJECT");
        }
    }

    public record PreviewRequest(String projectId, String text) {
    }

    private void validateProjectScope(QueryTermMappingCommand command) {
        if (command == null || command.scope() != QueryTermMappingScope.PROJECT) {
            return;
        }
        String projectId = command.projectId();
        requireNumericId(projectId, "projectId");
        if (projectService != null) {
            projectService.getEnabled(projectId);
        }
    }

    private static void requireMappingId(String id) {
        requireNumericId(id, "id");
    }

    private static void requireNumericId(String value, String field) {
        String safeValue = value == null ? "" : value.strip();
        if (safeValue.isBlank() || !safeValue.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(field + " must be a numeric identifier");
        }
    }

    private static String projectNotFoundMessage(QueryTermMappingCommand command) {
        String projectId = command == null ? "" : command.projectId();
        return "project not found: " + projectId;
    }

    private ResponseEntity<Object> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }
}
