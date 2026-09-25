package org.deep361.myddd.kernel.port.auth;

import java.util.Objects;
import java.util.Set;

/**
 * 当前登录用户（认证后的身份快照）。
 *
 * @param userId      用户唯一标识，不可为空白
 * @param username    用户名，不可为空白
 * @param roles       角色集合，可为 {@code null}（规范化为空）；内部防御性拷贝，元素不可为 {@code null} 或空白
 * @param permissions 权限集合，可为 {@code null}（规范化为空）；内部防御性拷贝，元素不可为 {@code null} 或空白
 */
public record AuthUser(String userId, String username, Set<String> roles, Set<String> permissions) {

    public AuthUser {
        Objects.requireNonNull(userId, "userId 不能为 null");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空白");
        }
        Objects.requireNonNull(username, "username 不能为 null");
        if (username.isBlank()) {
            throw new IllegalArgumentException("username 不能为空白");
        }
        roles = immutableCopy(roles, "roles");
        permissions = immutableCopy(permissions, "permissions");
    }

    /** 是否具备指定权限（精确匹配，不做通配）。 */
    public boolean hasPermission(String permission) {
        Objects.requireNonNull(permission, "permission 不能为 null");
        if (permission.isBlank()) {
            throw new IllegalArgumentException("permission 不能为空白");
        }
        return permissions.contains(permission);
    }

    private static Set<String> immutableCopy(Set<String> source, String field) {
        if (source == null) {
            return Set.of();
        }
        source.forEach(element -> {
            Objects.requireNonNull(element, field + " 的元素不能为 null");
            if (element.isBlank()) {
                throw new IllegalArgumentException(field + " 的元素不能为空白");
            }
        });
        return Set.copyOf(source);
    }
}
