package com.wish.rd.bootstrap.user;

/**
 * 后台用户管理服务端口。
 */
public interface UserAdminService {

    ManagedUserPage list(int current, int size, String keyword);

    ManagedUser create(String username, String password, String role, String avatar);

    ManagedUser update(String id, String username, String password, String role, String avatar);

    boolean delete(String id);

    boolean changePassword(String currentPassword, String newPassword);
}
