package com.wish.rd.bootstrap.user.controller.request;

public record UserCreateRequest(String username, String password, String role, String avatar) {
}
