package org.deep361.myddd.auth.satoken;

import cn.dev33.satoken.stp.StpUtil;
import org.deep361.myddd.auth.satoken.testing.SaTokenRequestScope;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.port.auth.AuthCredentials;
import org.deep361.myddd.kernel.port.auth.AuthUser;
import org.deep361.myddd.kernel.port.auth.CredentialVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 端口在 Sa-Token 侧的专有形状（{@code AB} 组）；跨厂商可比的那八条在 {@code SaTokenAuthPortContractTest}。 */
class SaTokenAuthPortTest {

    /** 每条用例各占一个 loginId（进程级内存 DAO 里的会话不会随作用域关闭而消失，见 49a 第 6 条第 ② 点）。 */
    private static final String USER = "cheney";
    private static final AuthCredentials CREDENTIALS = new AuthCredentials(USER, "correct");

    private final SaTokenRequestScope scope = new SaTokenRequestScope();

    @AfterEach
    void closeScopeAndRestoreThePermissionSource() {
        scope.close();
        SaTokenRequestScope.restoreDefaultPermissionSource();
    }

    /** 只实现 {@code stream()}：其余方法的 0/1/多语义由 Spring 的 default 给出（一手 ObjectProvider.java:85-95）。 */
    private static ObjectProvider<CredentialVerifier> verifiers(CredentialVerifier... source) {
        return new ObjectProvider<>() {
            @Override
            public Stream<CredentialVerifier> stream() {
                return Arrays.stream(source);
            }
        };
    }

    private static SaTokenAuthPort port(CredentialVerifier... verifiers) {
        return new SaTokenAuthPort(verifiers(verifiers));
    }

    /** 装上权限源、开作用域、走端口登录，返回这个端口。 */
    private SaTokenAuthPort loggedInPort(String loginId, List<String> permissions, List<String> roles) {
        SaTokenRequestScope.installByUser(Map.of(loginId, permissions), Map.of(loginId, roles));
        scope.open();
        SaTokenAuthPort port = port(credentials ->
                new AuthUser(loginId, USER, Set.copyOf(roles), Set.copyOf(permissions)));
        port.login(CREDENTIALS);
        return port;
    }

    /** AB1：username 落在 account session 的固定键上，值是 String。 */
    @Test
    void should_store_the_username_in_the_account_session_under_our_key() {
        String loginId = "49201";
        loggedInPort(loginId, List.of("order:read"), List.of("finance"));
        assertEquals(USER, StpUtil.getSessionByLoginId(loginId).get(SaTokenAuthPort.USERNAME_KEY));
    }

    /** AB2：token 的唯一出口是 Set-Cookie（厂商缺省 isWriteHeader=false，探针 49d 一手）。 */
    @Test
    void should_write_the_token_only_through_a_set_cookie_header() {
        loggedInPort("49202", List.of("order:read"), List.of());
        assertThat(scope.writtenHeaders()).singleElement()
                .asString().startsWith("add:Set-Cookie=satoken=");
    }

    /** AB3：校验器返回 null ⇒ 框架语义的 401，且一个 token 都没发。 */
    @Test
    void should_not_open_a_session_when_the_verifier_returns_null() {
        scope.open();
        SaTokenAuthPort port = port(credentials -> null);
        BizException failure = assertThrows(BizException.class, () -> port.login(CREDENTIALS));
        assertEquals(CommonErrorCode.UNAUTHORIZED, failure.getErrorCode());
        assertThat(scope.writtenHeaders()).isEmpty();
        assertTrue(port.currentUser().isEmpty());
    }

    /** AB4：校验器缺席 ⇒ 同一个 401，而且根本没走到上下文。 */
    @Test
    void should_reject_login_when_no_verifier_is_registered() {
        SaTokenAuthPort port = port();
        BizException failure = assertThrows(BizException.class, () -> port.login(CREDENTIALS));
        assertEquals(CommonErrorCode.UNAUTHORIZED, failure.getErrorCode());
    }

    /** AB5：两个校验器且没人被指认 ⇒ Spring 自己的歧义异常原样上抛，不由我们代裁。 */
    @Test
    void should_let_spring_report_an_ambiguous_verifier() {
        CredentialVerifier first = credentials -> new AuthUser("49205", USER, Set.of(), Set.of());
        CredentialVerifier second = credentials -> new AuthUser("49255", USER, Set.of(), Set.of());
        assertThrows(NoUniqueBeanDefinitionException.class,
                () -> port(first, second).login(CREDENTIALS));
    }

    /** AB6：绕过本端口的原生登录读得到身份，但我们的键不在，username 兜底成 userId。 */
    @Test
    void should_fall_back_to_the_login_id_when_the_username_was_never_written() {
        SaTokenRequestScope.installByUser(Map.of("49206", List.of("order:read")), Map.of());
        scope.open();
        StpUtil.login("49206");
        AuthUser user = port().currentUser().orElseThrow();
        assertEquals("49206", user.userId());
        assertEquals("49206", user.username());
    }

    /** AB7：端口只认精确匹配——厂商的通配命中在这里必须是 false（D-183 的钉子）。 */
    @Test
    void should_match_permissions_exactly_and_never_by_wildcard() {
        SaTokenAuthPort port = loggedInPort("49207", List.of("order:*"), List.of());
        assertTrue(StpUtil.hasPermission("49207", "order:read"), "前置条件：厂商侧的通配确实命中");
        assertFalse(port.hasPermission("order:read"));
        assertTrue(port.hasPermission("order:*"));
    }

    /** AB8：非法权限名快速失败，而且发生在上下文判定之前（无上下文也抛，不是归一成 false）。 */
    @Test
    void should_reject_an_illegal_permission_name_before_reading_the_context() {
        SaTokenAuthPort port = port();
        assertThrows(IllegalArgumentException.class, () -> port.hasPermission("  "));
        assertThrows(IllegalArgumentException.class, () -> port.hasPermission(null));
    }

    /** AB9：没有上下文的线程上登出是空操作（R9 的端口侧表达）。 */
    @Test
    void should_treat_logout_as_a_no_op_without_any_context() {
        SaTokenAuthPort port = port();
        port.logout();
        assertTrue(port.currentUser().isEmpty());
    }

    /** AB10：权限每次现读，不缓存——换掉权限源立刻生效。 */
    @Test
    void should_read_the_permission_source_on_every_call() {
        SaTokenAuthPort port = loggedInPort("49210", List.of("a:read"), List.of());
        assertTrue(port.hasPermission("a:read"));
        SaTokenRequestScope.installByUser(Map.of("49210", List.of("b:read")), Map.of());
        assertFalse(port.hasPermission("a:read"), "换源之后还命中旧权限，说明端口缓存了列表");
        assertTrue(port.hasPermission("b:read"));
    }

    /** AB11：权限源给重复项时，端口的集合已规范化（内核 AuthUser 的 Set.copyOf 接住，桥本身原样转交）。 */
    @Test
    void should_hand_the_kernel_a_normalized_identity() {
        SaTokenAuthPort port = loggedInPort("49211", List.of("z:read", "a:read", "z:read"), List.of("finance", "finance"));
        AuthUser user = port.currentUser().orElseThrow();
        assertThat(user.permissions()).containsExactlyInAnyOrder("z:read", "a:read");
        assertThat(user.roles()).containsExactly("finance");
    }

    /** AB12：凭据原样、且只递一次给校验器。 */
    @Test
    void should_hand_the_credentials_to_the_verifier_exactly_once() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<AuthCredentials> seen = new AtomicReference<>();
        String loginId = "49212";
        SaTokenRequestScope.installByUser(Map.of(loginId, List.of()), Map.of());
        scope.open();
        port(credentials -> {
            calls.incrementAndGet();
            seen.set(credentials);
            return new AuthUser(loginId, USER, Set.of(), Set.of());
        }).login(CREDENTIALS);
        assertEquals(1, calls.get());
        assertSame(CREDENTIALS, seen.get());
    }
}
