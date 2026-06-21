package com.wish.rd.bootstrap.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wish.rd.bootstrap.user.controller.vo.UserPageVO;
import com.wish.rd.bootstrap.user.controller.vo.UserVO;
import com.wish.rd.bootstrap.user.dao.entity.AdminUserDO;
import com.wish.rd.bootstrap.user.dao.mapper.AdminUserMapper;
import com.wish.rd.bootstrap.user.service.UserAdminService;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/**
 * PostgreSQL 后台用户管理服务。
 */
@Service
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresUserAdminService implements UserAdminService {

    private static final long DEFAULT_ADMIN_ID = 1L;

    private final AdminUserMapper mapper;
    private final SnowflakeIdGenerator idGenerator;

    public PostgresUserAdminService(AdminUserMapper mapper, SnowflakeIdGenerator idGenerator) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        ensureDefaultAdmin();
    }

    @Override
    public UserPageVO<UserVO> list(int current, int size, String keyword) {
        int page = Math.max(1, current);
        int pageSize = Math.max(1, size);
        QueryWrapper<AdminUserDO> query = new QueryWrapper<>();
        String normalized = normalizeKeyword(keyword);
        if (normalized != null) {
            query.and(wrapper -> wrapper
                    .apply("LOWER(username) LIKE {0}", "%" + normalized + "%")
                    .or()
                    .apply("LOWER(role) LIKE {0}", "%" + normalized + "%"));
        }
        query.orderByAsc("created_at").orderByAsc("id");
        List<UserVO> all = mapper.selectList(query)
                .stream()
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
        OffsetDateTime now = OffsetDateTime.now();
        AdminUserDO row = new AdminUserDO();
        row.id = idGenerator.nextId();
        row.username = normalizedUsername;
        row.role = normalizeRole(role);
        row.avatar = emptyIfNull(avatar);
        row.passwordHash = UserPasswordHashing.sha256(normalizedPassword);
        row.createdAt = now;
        row.updatedAt = now;
        mapper.insert(row);
        return toUser(row);
    }

    @Override
    public UserVO update(String id, String username, String password, String role, String avatar) {
        AdminUserDO existing = requireUser(id);
        String normalizedUsername = normalize(username);
        if (normalizedUsername != null) {
            ensureUsernameAvailable(normalizedUsername, existing.id);
        }
        existing.username = normalizedUsername == null ? existing.username : normalizedUsername;
        existing.role = role == null ? existing.role : normalizeRole(role);
        existing.avatar = avatar == null ? existing.avatar : emptyIfNull(avatar);
        if (password != null && !password.isBlank()) {
            existing.passwordHash = UserPasswordHashing.sha256(password);
        }
        existing.updatedAt = OffsetDateTime.now();
        mapper.updateById(existing);
        return toUser(existing);
    }

    @Override
    public boolean delete(String id) {
        AdminUserDO existing = requireUser(id);
        if ("admin".equals(existing.username)) {
            throw new IllegalArgumentException("default admin user cannot be deleted");
        }
        mapper.deleteById(existing.id);
        return true;
    }

    @Override
    public boolean changePassword(String currentPassword, String newPassword) {
        String current = requireText(currentPassword, "currentPassword must not be blank");
        String next = requireText(newPassword, "newPassword must not be blank");
        AdminUserDO admin = findByUsername("admin");
        if (admin == null) {
            throw new NoSuchElementException("user not found: admin");
        }
        if (!admin.passwordHash.equals(UserPasswordHashing.sha256(current))) {
            throw new IllegalArgumentException("currentPassword is incorrect");
        }
        admin.passwordHash = UserPasswordHashing.sha256(next);
        admin.updatedAt = OffsetDateTime.now();
        mapper.updateById(admin);
        return true;
    }

    private void ensureDefaultAdmin() {
        if (findByUsername("admin") != null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        AdminUserDO row = new AdminUserDO();
        row.id = DEFAULT_ADMIN_ID;
        row.username = "admin";
        row.role = "admin";
        row.avatar = "";
        row.passwordHash = UserPasswordHashing.sha256("admin");
        row.createdAt = now;
        row.updatedAt = now;
        mapper.insert(row);
    }

    private AdminUserDO requireUser(String id) {
        AdminUserDO user = mapper.selectById(parseId(id));
        if (user == null) {
            throw new NoSuchElementException("user not found: " + id);
        }
        return user;
    }

    private void ensureUsernameAvailable(String username, Long currentId) {
        AdminUserDO existing = findByUsername(username);
        if (existing != null && !existing.id.equals(currentId)) {
            throw new IllegalArgumentException("username already exists: " + username);
        }
    }

    private AdminUserDO findByUsername(String username) {
        QueryWrapper<AdminUserDO> query = new QueryWrapper<>();
        query.eq("username", username);
        return mapper.selectOne(query);
    }

    private UserVO toUser(AdminUserDO row) {
        return new UserVO(
                idString(row.id),
                row.username,
                row.role,
                safe(row.avatar),
                toInstantString(row.createdAt),
                toInstantString(row.updatedAt)
        );
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
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private String toInstantString(OffsetDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        return dateTime.toInstant().toString();
    }

    private long parseId(String id) {
        return Long.parseLong(id);
    }

    private String idString(Long id) {
        return id == null ? "" : id.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
