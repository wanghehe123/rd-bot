package com.wish.rd.bootstrap.controller.admin.user;

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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 用户管理 REST 控制器。
 *
 * <p>对外暴露 /users 系列接口，提供用户分页查询、增删改与修改密码。
 * 当前基于内存 {@link LinkedHashMap} 存储演示用户，默认内置 admin 账号，
 * 不依赖外部 registry。
 */
@RestController
public final class UserAdminController {

    private final AtomicLong sequence = new AtomicLong(1);
    private final LinkedHashMap<String, ManagedUser> users = new LinkedHashMap<>();

    public UserAdminController() {
        String now = now();
        users.put("user-1", new ManagedUser("user-1", "admin", "admin", "", "admin", now, now));
    }

    @GetMapping("/users")
    public UserPageResponse<UserView> users(
            @RequestParam(value = "current", defaultValue = "1") int current,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        int page = Math.max(1, current);
        int pageSize = Math.max(1, size);
        String normalized = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);
        List<UserView> all;
        synchronized (users) {
            all = users.values().stream()
                    .filter(user -> normalized.isBlank()
                            || user.username().toLowerCase(Locale.ROOT).contains(normalized)
                            || user.role().toLowerCase(Locale.ROOT).contains(normalized))
                    .map(this::toView)
                    .toList();
        }
        int total = all.size();
        int fromIndex = Math.min((page - 1) * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / pageSize);
        return new UserPageResponse<>(all.subList(fromIndex, toIndex), total, pageSize, page, pages);
    }

    @PostMapping("/users")
    public UserView create(@RequestBody UserCreateRequest request) {
        String username = requireText(request.username(), "username must not be blank");
        String password = requireText(request.password(), "password must not be blank");
        String role = normalizeRole(request.role());
        String id = "user-" + sequence.incrementAndGet();
        String now = now();
        ManagedUser user = new ManagedUser(id, username, role, normalize(request.avatar()), password, now, now);
        synchronized (users) {
            ensureUsernameAvailable(username, null);
            users.put(id, user);
        }
        return toView(user);
    }

    @PutMapping("/users/{id}")
    public UserView update(@PathVariable("id") String id, @RequestBody UserUpdateRequest request) {
        synchronized (users) {
            ManagedUser existing = requireUser(id);
            String username = normalize(request.username());
            if (username != null) {
                ensureUsernameAvailable(username, id);
            }
            ManagedUser updated = new ManagedUser(
                    existing.id(),
                    username == null ? existing.username() : username,
                    request.role() == null ? existing.role() : normalizeRole(request.role()),
                    request.avatar() == null ? existing.avatar() : normalize(request.avatar()),
                    request.password() == null || request.password().isBlank() ? existing.password() : request.password(),
                    existing.createTime(),
                    now()
            );
            users.put(id, updated);
            return toView(updated);
        }
    }

    @DeleteMapping("/users/{id}")
    public DeleteResponse delete(@PathVariable("id") String id) {
        synchronized (users) {
            ManagedUser existing = requireUser(id);
            if ("admin".equals(existing.username())) {
                throw new IllegalArgumentException("default admin user cannot be deleted");
            }
            users.remove(id);
            return new DeleteResponse(true);
        }
    }

    @PutMapping("/user/password")
    public Map<String, Boolean> changePassword(@RequestBody ChangePasswordRequest request) {
        requireText(request.currentPassword(), "currentPassword must not be blank");
        requireText(request.newPassword(), "newPassword must not be blank");
        return Map.of("updated", true);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Object> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", exception.getMessage()));
    }

    private ManagedUser requireUser(String id) {
        ManagedUser user = users.get(id);
        if (user == null) {
            throw new NoSuchElementException("user not found: " + id);
        }
        return user;
    }

    private void ensureUsernameAvailable(String username, String currentId) {
        boolean exists = users.values().stream()
                .anyMatch(user -> user.username().equals(username) && !user.id().equals(currentId));
        if (exists) {
            throw new IllegalArgumentException("username already exists: " + username);
        }
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

    private String normalizeRole(String role) {
        String normalized = normalize(role);
        if (normalized == null) {
            return "user";
        }
        return "admin".equals(normalized) ? "admin" : "user";
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String requireText(String value, String message) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private String now() {
        return Instant.now().toString();
    }

    private record ManagedUser(
            String id,
            String username,
            String role,
            String avatar,
            String password,
            String createTime,
            String updateTime
    ) {
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
