package com.wish.rd.bootstrap.user.controller.request;

public record UserUpdateRequest(String username, String password, String role, String avatar) {
}
