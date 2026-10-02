package org.deep361.myddd.authfixture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.deep361.myddd.kernel.port.auth.AuthCredentials;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.deep361.myddd.kernel.port.auth.AuthUser;

/**
 * {@code AT} 组的端口替身：{@code currentUser()} 可编程、{@code granted} 是精确匹配的权限集合、
 * {@code permissionCalls} 按调用顺序记录被问的权限串（{@code AT1} 的"逐字等于声明顺序"与
 * {@code AT3} 的短路计数器都读它）。
 *
 * <p>{@code login}/{@code logout} 抛 {@code UnsupportedOperationException}：本组一次都不会调，
 * 调到了就是测试写错（与 {@code 48f} 夹具纪律一致）。
 */
public class FakeAuthPort implements AuthPort {

    /** 被问过的权限串，含顺序；{@code AT3}/{@code AT4}/{@code AT8}/{@code AT10}/{@code AT12}/{@code AT13} 的计数器。 */
    public final List<String> permissionCalls = new ArrayList<>();

    /** 精确匹配的授权集合（内核 {@code AuthUser#hasPermission} 同语义）。 */
    public final Set<String> granted = new LinkedHashSet<>();

    private Optional<AuthUser> current = Optional.empty();

    public FakeAuthPort() {
    }

    public FakeAuthPort(String... permissions) {
        grant(permissions);
    }

    public void grant(String... permissions) {
        Collections.addAll(this.granted, permissions);
    }

    public void loggedInAs(String userId) {
        this.current = Optional.of(new AuthUser(userId, userId + "-name", Set.of(), this.granted));
    }

    public void loggedInAs(String userId, String... permissions) {
        grant(permissions);
        loggedInAs(userId);
    }

    public void loggedOut() {
        this.current = Optional.empty();
    }

    @Override
    public Optional<AuthUser> currentUser() {
        return this.current;
    }

    @Override
    public void login(AuthCredentials credentials) {
        throw new UnsupportedOperationException("AT 组夹具不调 login，调到了就是测试写错");
    }

    @Override
    public void logout() {
        throw new UnsupportedOperationException("AT 组夹具不调 logout，调到了就是测试写错");
    }

    @Override
    public boolean hasPermission(String permission) {
        this.permissionCalls.add(permission);
        return this.granted.contains(permission);
    }
}
