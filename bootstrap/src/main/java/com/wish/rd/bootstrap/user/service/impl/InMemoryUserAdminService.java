package com.wish.rd.bootstrap.user.service.impl;

import com.wish.rd.bootstrap.user.controller.vo.UserPageVO;
import com.wish.rd.bootstrap.user.controller.vo.UserVO;
import com.wish.rd.bootstrap.user.service.UserAdminService;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 非 PostgreSQL 模式下的用户管理实现，保证本地测试和 demo 仍可直接运行。
 */
@Service
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
public final class InMemoryUserAdminService implements UserAdminService {

    private static final String DEFAULT_ADMIN_ID = "1";

    private final Map<String, StoredUser> users = new ConcurrentHashMap<>();
    private final SnowflakeIdGenerator idGenerator;

    public InMemoryUserAdminService(SnowflakeIdGenerator idGenerator) {
        this.idGenerator = idGenerator;
        String now = now();
        users.put(DEFAULT_ADMIN_ID, new StoredUser(
                DEFAULT_ADMIN_ID,
                "admin",
                "admin",
                "",
                UserPasswordHashing.sha256("admin"),
                now,
                now
        ));
    }

    @Override
    public UserPageVO<UserVO> list(int current, int size, String keyword) {
        int page = Math.max(1, current);
        int pageSize = Math.max(1, size);
        String normalized = normalizeKeyword(keyword);
        List<UserVO> all = users.values()
                .stream()
                .filter(user -> normalized == null
                        || user.username().toLowerCase(Locale.ROOT).contains(normalized)
                        || user.role().toLowerCase(Locale.ROOT).contains(normalized))
                .sorted(Comparator.comparing(StoredUser::createTime).thenComparing(StoredUser::id))
                .map(this::toUser)
                .toList();
        int total = all.size();
        int fromIndex = Math.min((page - 1) * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / pageSize);
        return new UserPageVO<>(all.subList(fromIndex, toIndex), total, pageSize, page, pages);
    }

    @Override
    public UserVO create(String username, String password, String role, String avatar) {
        String normalizedUsername = requireText(username, "username must not be blank");
        String normalizedPassword = requireText(password, "password must not be blank");
        ensureUsernameAvailable(normalizedUsername, null);
        String id = idGenerator.nextIdString();
        String now = now();
        StoredUser user = new StoredUser(
                id,
                normalizedUsername,
                normalizeRole(role),
                emptyIfNull(avatar),
                UserPasswordHashing.sha256(normalizedPassword),
                now,
                now
        );
        users.put(id, user);
        return toUser(user);
    }

    @Override
    public UserVO update(String id, String username, String password, String role, String avatar) {
        StoredUser existing = requireUser(id);
        String normalizedUsername = normalize(username);
        if (normalizedUsername != null) {
            ensureUsernameAvailable(normalizedUsername, id);
        }
        StoredUser updated = new StoredUser(
                existing.id(),
                normalizedUsername == null ? existing.username() : normalizedUsername,
                role == null ? existing.role() : normalizeRole(role),
                avatar == null ? existing.avatar() : emptyIfNull(avatar),
                password == null || password.isBlank() ? existing.passwordHash() : UserPasswordHashing.sha256(password),
                existing.createTime(),
                now()
        );
        users.put(id, updated);
        return toUser(updated);
    }

    @Override
    public boolean delete(String id) {
        StoredUser existing = requireUser(id);
        if ("admin".equals(existing.username())) {
            throw new IllegalArgumentException("default admin user cannot be deleted");
        }
        users.remove(id);
        return true;
    }

    @Override
    public boolean changePassword(String currentPassword, String newPassword) {
        String current = requireText(currentPassword, "currentPassword must not be blank");
        String next = requireText(newPassword, "newPassword must not be blank");
        StoredUser admin = requireUser(DEFAULT_ADMIN_ID);
        if (!admin.passwordHash().equals(UserPasswordHashing.sha256(current))) {
            throw new IllegalArgumentException("currentPassword is incorrect");
        }
        users.put(DEFAULT_ADMIN_ID, new StoredUser(
                admin.id(),
                admin.username(),
                admin.role(),
                admin.avatar(),
                UserPasswordHashing.sha256(next),
                admin.createTime(),
                now()
        ));
        return true;
    }

    private StoredUser requireUser(String id) {
        StoredUser user = users.get(id);
        if (user == null) {
            throw new NoSuchElementException("user not found: " + id);
        }
        return user;
    }

    private void ensureUsernameAvailable(String username, String currentId) {
        boolean exists = users.values()
                .stream()
                .anyMatch(user -> user.username().equals(username) && !user.id().equals(currentId));
        if (exists) {
            throw new IllegalArgumentException("username already exists: " + username);
        }
    }

    private UserVO toUser(StoredUser user) {
        return new UserVO(user.id(), user.username(), user.role(), user.avatar(), user.createTime(), user.updateTime());
    }

    private String normalizeRole(String role) {
        String normalized = normalize(role);
        if (normalized == null) {
            return "user";
        }
        return "admin".equals(normalized) ? "admin" : "user";
    }

    private String normalizeKeyword(String value) {
        String normalized = normalize(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String emptyIfNull(String value) {
        String normalized = value == null ? null : value.strip();
        return normalized == null ? "" : normalized;
    }

    private String requireText(String value, String message) {
        String normalized = value == null ? null : value.strip();
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private String now() {
        return Instant.now().toString();
    }

    private record StoredUser(
            String id,
            String username,
            String role,
            String avatar,
            String passwordHash,
            String createTime,
            String updateTime
    ) {
    }
}
