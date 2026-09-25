package org.deep361.myddd.kernel.port.auth;

import java.util.Objects;

/**
 * 登录凭据。
 *
 * <p>{@link #toString()} 刻意不输出密码，避免凭据进入日志/异常信息。
 *
 * @param username 用户名，不可为空白
 * @param password 密码，不可为空白
 */
public record AuthCredentials(String username, String password) {

    public AuthCredentials {
        Objects.requireNonNull(username, "username 不能为 null");
        if (username.isBlank()) {
            throw new IllegalArgumentException("username 不能为空白");
        }
        Objects.requireNonNull(password, "password 不能为 null");
        if (password.isBlank()) {
            throw new IllegalArgumentException("password 不能为空白");
        }
    }

    @Override
    public String toString() {
        return "AuthCredentials[username=" + username + ", password=***]";
    }
}
