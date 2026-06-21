package com.wish.rd.bootstrap.controller.admin.user;

import com.wish.rd.bootstrap.user.ManagedUser;
import com.wish.rd.bootstrap.user.ManagedUserPage;
import com.wish.rd.bootstrap.user.UserAdminService;
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

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 用户管理 REST 控制器。
 *
 * <p>对外暴露 /users 系列接口，提供用户分页查询、增删改与修改密码。
 */
@RestController
public final class UserAdminController {

    private final UserAdminService userAdminService;

    public UserAdminController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @GetMapping("/users")
    public UserPageResponse<UserView> users(
            @RequestParam(value = "current", defaultValue = "1") int current,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        ManagedUserPage page = userAdminService.list(current, size, keyword);
        return new UserPageResponse<>(
                page.records().stream().map(this::toView).toList(),
                page.total(),
                page.size(),
                page.current(),
                page.pages()
        );
    }

    @PostMapping("/users")
    public UserView create(@RequestBody UserCreateRequest request) {
        return toView(userAdminService.create(request.username(), request.password(), request.role(), request.avatar()));
    }

    @PutMapping("/users/{id}")
    public UserView update(@PathVariable("id") String id, @RequestBody UserUpdateRequest request) {
        return toView(userAdminService.update(id, request.username(), request.password(), request.role(), request.avatar()));
    }

    @DeleteMapping("/users/{id}")
    public DeleteResponse delete(@PathVariable("id") String id) {
        return new DeleteResponse(userAdminService.delete(id));
    }

    @PutMapping("/user/password")
    public Map<String, Boolean> changePassword(@RequestBody ChangePasswordRequest request) {
        return Map.of("updated", userAdminService.changePassword(request.currentPassword(), request.newPassword()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Object> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", exception.getMessage()));
    }

    private UserView toView(ManagedUser user) {
        return new UserView(
                user.id(),
                user.username(),
                user.role(),
                user.avatar(),
                user.createTime(),
                user.updateTime()
        );
    }

    public record UserView(
            String id,
            String username,
            String role,
            String avatar,
            String createTime,
            String updateTime
    ) {
    }

    public record UserPageResponse<T>(List<T> records, int total, int size, int current, int pages) {
    }

    public record UserCreateRequest(String username, String password, String role, String avatar) {
    }

    public record UserUpdateRequest(String username, String password, String role, String avatar) {
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {
    }

    public record DeleteResponse(boolean deleted) {
    }
}
