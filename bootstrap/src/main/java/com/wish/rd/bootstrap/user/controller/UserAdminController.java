package com.wish.rd.bootstrap.user.controller;

import com.wish.rd.bootstrap.user.controller.request.ChangePasswordRequest;
import com.wish.rd.bootstrap.user.controller.request.UserCreateRequest;
import com.wish.rd.bootstrap.user.controller.request.UserUpdateRequest;
import com.wish.rd.bootstrap.user.controller.vo.DeleteVO;
import com.wish.rd.bootstrap.user.controller.vo.UserPageVO;
import com.wish.rd.bootstrap.user.controller.vo.UserVO;
import com.wish.rd.bootstrap.user.service.UserAdminService;
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
    public UserPageVO<UserVO> users(
            @RequestParam(value = "current", defaultValue = "1") int current,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        return userAdminService.list(current, size, keyword);
    }

    @PostMapping("/users")
    public UserVO create(@RequestBody UserCreateRequest request) {
        return userAdminService.create(request.username(), request.password(), request.role(), request.avatar());
    }

    @PutMapping("/users/{id}")
    public UserVO update(@PathVariable("id") String id, @RequestBody UserUpdateRequest request) {
        return userAdminService.update(id, request.username(), request.password(), request.role(), request.avatar());
    }

    @DeleteMapping("/users/{id}")
    public DeleteVO delete(@PathVariable("id") String id) {
        return new DeleteVO(userAdminService.delete(id));
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
}
