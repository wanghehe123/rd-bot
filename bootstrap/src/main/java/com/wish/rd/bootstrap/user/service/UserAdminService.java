package com.wish.rd.bootstrap.user.service;

import com.wish.rd.bootstrap.user.controller.vo.UserPageVO;
import com.wish.rd.bootstrap.user.controller.vo.UserVO;

/**
 * 后台用户管理服务端口。
 */
public interface UserAdminService {

    UserPageVO<UserVO> list(int current, int size, String keyword);

    UserVO create(String username, String password, String role, String avatar);

    UserVO update(String id, String username, String password, String role, String avatar);

    boolean delete(String id);

    boolean changePassword(String currentPassword, String newPassword);
}
