package org.deep361.myddd.testing.contract;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.port.auth.AuthCredentials;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.deep361.myddd.kernel.port.auth.AuthUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AuthPort} 的跨厂商契约：Sa-Token 与 Spring Security 必须给出同一组可观察结果。
 *
 * <p>两个厂商的身份存储机制完全不同（Sa-Token = 自己的 token/上下文盒子，Spring Security =
 * {@code SecurityContextHolder} 的 ThreadLocal），因此"怎么把身份装进当前线程"必须是子类的缝
 * （{@link #openRequestScope()}/{@link #closeRequestScope()}），而**装完之后端口的行为**由本基类钉死。
 *
 * <p>{@link #closeRequestScope()} 在 {@code @AfterEach} 里无条件调用：登录态漏到下一个用例是最难查的一类
 * 串号缺陷，本基类把它变成确定会红的用例而不是偶发。
 */
public abstract class AuthPortContractTestBase {

    /** 被测端口。 */
    protected abstract AuthPort authPort();

    /** 凭据正确的一组用户名/口令（必须与 {@link #knownUser()} 对应）。 */
    protected abstract AuthCredentials validCredentials();

    /** 口令错误的一组凭据（用户名必须存在，否则两个厂商会走"用户不存在"与"口令错误"两条不同分支）。 */
    protected abstract AuthCredentials brokenCredentials();

    /** {@link #validCredentials()} 登录成功后应当得到的用户。 */
    protected abstract AuthUser knownUser();

    /** 建立"当前线程有请求上下文"的作用域（Sa-Token 侧放上下文盒子，Spring Security 侧设 ThreadLocal）。 */
    protected abstract void openRequestScope();

    /** 拆掉上下文并把两侧 ThreadLocal 清干净。 */
    protected abstract void closeRequestScope();

    /** 在**没有**请求上下文的线程上读当前用户（R9：必须返回空，不许抛）。 */
    protected abstract Optional<AuthUser> currentUserWithoutRequestScope();

    @AfterEach
    void alwaysCloseTheScope() {
        closeRequestScope();
    }

    /** AUTH-C1：登录成功后的 {@code currentUser()} 必须逐字段等于用户库里的形状。 */
    @Test
    void should_expose_the_logged_in_user() {
        openRequestScope();
        authPort().login(validCredentials());
        Optional<AuthUser> current = authPort().currentUser();
        assertTrue(current.isPresent(), "登录成功后 currentUser 仍为空");
        AuthUser user = current.get();
        assertEquals(knownUser().userId(), user.userId());
        assertEquals(knownUser().username(), user.username());
        assertEquals(knownUser().roles(), user.roles());
        assertEquals(knownUser().permissions(), user.permissions());
    }

    /** AUTH-C2：口令错误 ⇒ 框架语义的 401，且绝不留下半截登录态。 */
    @Test
    void should_reject_wrong_credentials_with_unauthorized() {
        openRequestScope();
        BizException failure = assertThrows(BizException.class, () -> authPort().login(brokenCredentials()));
        assertEquals(CommonErrorCode.UNAUTHORIZED, failure.getErrorCode());
        assertTrue(authPort().currentUser().isEmpty(), "登录失败却已经有当前用户");
        assertFalse(authPort().hasPermission("order:read"));
    }

    /** AUTH-C3：登出后身份与权限一起消失。 */
    @Test
    void should_drop_identity_and_permissions_on_logout() {
        openRequestScope();
        authPort().login(validCredentials());
        assertTrue(authPort().hasPermission("order:read"), "前置条件：登录时必须具备该权限");
        authPort().logout();
        assertTrue(authPort().currentUser().isEmpty());
        assertFalse(authPort().hasPermission("order:read"));
    }

    /** AUTH-C4：已授予的权限为 true、未授予的为 false——未授予**不是异常**（授权判定与认证失败是两回事）。 */
    @Test
    void should_answer_permission_questions_without_throwing() {
        openRequestScope();
        authPort().login(validCredentials());
        assertTrue(authPort().hasPermission("order:read"));
        assertFalse(authPort().hasPermission("order:refund"));
    }

    /** AUTH-C5：空白权限名快速失败（端口层不猜意图）。 */
    @Test
    void should_reject_an_illegal_permission_name() {
        openRequestScope();
        authPort().login(validCredentials());
        assertThrows(IllegalArgumentException.class, () -> authPort().hasPermission("  "));
        assertThrows(IllegalArgumentException.class, () -> authPort().hasPermission(null));
    }

    /** AUTH-C6/R9：没有请求上下文（批处理线程、MQ 消费线程）时读身份必须是空、判权限必须是 false，一律不抛。 */
    @Test
    void should_answer_quietly_outside_a_request_scope() {
        closeRequestScope();
        assertTrue(currentUserWithoutRequestScope().isEmpty(),
                "无上下文线程上 currentUser 必须为空（R9），实际拿到了用户");
        assertFalse(authPort().hasPermission("order:read"),
                "无上下文线程上 hasPermission 必须为 false（R9），实际为 true 或抛了异常");
    }

    /** AUTH-C7：登出后重新登录必须恢复完整身份（钉住厂商侧的会话/上下文重建，而不是"第一次侥幸能用"）。 */
    @Test
    void should_restore_the_full_identity_after_re_login() {
        openRequestScope();
        authPort().login(validCredentials());
        authPort().logout();
        authPort().login(validCredentials());
        assertEquals(knownUser().userId(), authPort().currentUser().orElseThrow().userId());
        assertEquals(Set.of("order:read"), Set.copyOf(authPort().currentUser().orElseThrow().permissions()
                .stream().filter("order:read"::equals).toList()));
    }

    /** AUTH-C8：未登录就 logout 不许抛（幂等地"确认没有身份"是端口该做的事）。 */
    @Test
    void should_tolerate_logout_without_a_session() {
        openRequestScope();
        authPort().logout();
        assertTrue(authPort().currentUser().isEmpty());
    }
}
