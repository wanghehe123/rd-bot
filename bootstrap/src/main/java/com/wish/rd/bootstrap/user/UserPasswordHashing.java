package com.wish.rd.bootstrap.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 用户密码摘要工具。
 */
public final class UserPasswordHashing {

    private UserPasswordHashing() {
    }

    public static String sha256(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }
}
