package com.wish.rd.bootstrap.user.controller.request;

public record ChangePasswordRequest(String currentPassword, String newPassword) {
}
