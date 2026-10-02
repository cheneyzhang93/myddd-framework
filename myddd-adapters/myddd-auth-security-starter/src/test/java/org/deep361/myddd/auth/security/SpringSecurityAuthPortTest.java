package org.deep361.myddd.auth.security;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.deep361.myddd.auth.security.testing.SecurityContextHolderScope;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.auth.AuthCredentials;
import org.deep361.myddd.kernel.port.auth.AuthUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 端口在 Spring Security 侧的专有形状（{@code AB} 组）；跨厂商可比的那八条在 {@code SpringSecurityAuthPortContractTest}。 */
class SpringSecurityAuthPortTest {

    private final SecurityContextHolderScope scope = new SecurityContextHolderScope();

    @AfterEach
    void clearTheHolder() {
        this.scope.close();
    }

    /** 进程内用户库：四个形状各钉一支失败路径，其余用户名一律给"管理员 + order:read"。 */
    private static final class Store {
        static final String PASSWORD = "correct-horse";

        static AuthenticationManager managerFor() {
            DaoAuthenticationProvider provider = new DaoAuthenticationProvider(username -> {
                if ("store-down".equals(username)) {
                    throw new IllegalStateException("store down");
                }
                if ("unknown".equals(username)) {
                    throw new UsernameNotFoundException("unknown");
                }
                if ("disabled".equals(username)) {
                    return User.withUsername("disabled").password("{noop}" + PASSWORD).disabled(true)
                            .authorities("ROLE_admin").build();
                }
                if ("raw".equals(username)) {
                    return User.withUsername("raw").password(PASSWORD).authorities("ROLE_admin").build();
                }
                return User.withUsername(username).password("{noop}" + PASSWORD)
                        .authorities("ROLE_admin", "order:read").build();
            });
            return new ProviderManager(provider);
        }
    }

    /** 只实现 {@code stream()} 的 ObjectProvider 替身，语义与 {@code 49e} 那份逐字相同。 */
    private static ObjectProvider<AuthenticationManager> managers(AuthenticationManager... source) {
        List<AuthenticationManager> fixed = List.of(source);
        return new ObjectProvider<>() {
            @Override
            public java.util.stream.Stream<AuthenticationManager> stream() {
                return fixed.stream();
            }
        };
    }

    private static SpringSecurityAuthPort port(ObjectProvider<AuthenticationManager> provider) {
        return new SpringSecurityAuthPort(provider);
    }

    /** AB1：真认证链登录成功之后，同线程下一次 currentUser() 读得到，且两格身份字符串同源。 */
    @Test
    void should_install_the_authenticated_identity_for_the_same_thread() {
        SpringSecurityAuthPort port = port(managers(Store.managerFor()));
        port.login(new AuthCredentials("alice", Store.PASSWORD));
        AuthUser user = port.currentUser().orElseThrow();
        assertThat(user.userId()).isEqualTo("alice").isEqualTo(user.username());
        assertThat(user.permissions()).contains("order:read", "ROLE_admin");
    }

    /** AB2：厂商往结果 token 上加了 FACTOR_PASSWORD（第一句），端口读到的权限集里没有它（第二句）。 */
    @Test
    void should_drop_the_factor_authority_the_vendor_appends_after_success() {
        Authentication raw = Store.managerFor().authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("alice", Store.PASSWORD));
        assertThat(raw.getAuthorities()).anyMatch(FactorGrantedAuthority.class::isInstance);
        this.scope.open(raw);
        SpringSecurityAuthPort port = port(managers());
        assertThat(port.currentUser().orElseThrow().permissions())
                .doesNotContain("FACTOR_PASSWORD", "PASSWORD");
    }

    /** AB3：口令错 ⇒ UNAUTHORIZED 且 holder 干净（失败不留半截身份）。 */
    @Test
    void should_translate_rejected_credentials_into_unauthorized_without_installing_anything() {
        SpringSecurityAuthPort port = port(managers(Store.managerFor()));
        BizException failure = assertThrows(BizException.class,
                () -> port.login(new AuthCredentials("alice", "wrong")));
        assertEquals(CommonErrorCode.UNAUTHORIZED, failure.getErrorCode());
        assertThat(failure.getCause()).isInstanceOf(AuthenticationException.class);
        assertThat(port.currentUser()).isEmpty();
    }

    /** AB4：用户库故障走另一支——SYSTEM_ERROR 且 cause 链保住原厂异常。 */
    @Test
    void should_translate_a_broken_user_store_into_system_error() {
        SpringSecurityAuthPort port = port(managers(Store.managerFor()));
        BizException failure = assertThrows(BizException.class,
                () -> port.login(new AuthCredentials("store-down", Store.PASSWORD)));
        assertEquals(CommonErrorCode.SYSTEM_ERROR, failure.getErrorCode());
        assertThat(failure.getCause()).isInstanceOf(InternalAuthenticationServiceException.class);
        assertThat(failure.getCause().getCause()).isInstanceOf(IllegalStateException.class);
        assertThat(port.currentUser()).isEmpty();
    }

    /** AB5：库里存无前缀原始口令是配置事实，不是认证事实 ⇒ 原样上抛，一支都不翻译。 */
    @Test
    void should_not_translate_a_malformed_stored_password_into_an_authentication_failure() {
        SpringSecurityAuthPort port = port(managers(Store.managerFor()));
        assertThrows(IllegalArgumentException.class,
                () -> port.login(new AuthCredentials("raw", Store.PASSWORD)));
    }

    /** AB6：未知用户被厂商 hide 成 BadCredentials、停用用户是 DisabledException——两支都到 UNAUTHORIZED。 */
    @Test
    void should_report_both_hidden_unknown_user_and_disabled_user_as_unauthorized() {
        SpringSecurityAuthPort unknownPort = port(managers(Store.managerFor()));
        BizException unknown = assertThrows(BizException.class,
                () -> unknownPort.login(new AuthCredentials("unknown", Store.PASSWORD)));
        assertEquals(CommonErrorCode.UNAUTHORIZED, unknown.getErrorCode());

        SpringSecurityAuthPort disabledPort = port(managers(Store.managerFor()));
        BizException disabled = assertThrows(BizException.class,
                () -> disabledPort.login(new AuthCredentials("disabled", Store.PASSWORD)));
        assertEquals(CommonErrorCode.UNAUTHORIZED, disabled.getErrorCode());
        assertThat(disabled.getCause()).isInstanceOf(DisabledException.class);
    }

    /** AB7：没有认证链 ⇒ 一条含两条出路的 FrameworkException，且读面完全不受影响。 */
    @Test
    void should_fail_loudly_at_login_time_when_no_authentication_manager_exists() {
        SpringSecurityAuthPort port = port(managers());
        this.scope.open(SecurityContextHolderScope.authenticated("carol", List.of("order:read")));
        FrameworkException failure = assertThrows(FrameworkException.class,
                () -> port.login(new AuthCredentials("dave", Store.PASSWORD)));
        assertThat(failure.getMessage()).contains("AuthenticationManager", "myddd.auth.provider", "satoken");
        assertThat(port.currentUser().orElseThrow().userId()).isEqualTo("carol");
        assertTrue(port.hasPermission("order:read"));
    }

    /** AB8：两个 manager 且无人裁决 ⇒ 让 Spring 自己抛，不替它挑一个。 */
    @Test
    void should_let_spring_report_an_undecided_authentication_manager() {
        SpringSecurityAuthPort port = port(managers(Store.managerFor(), Store.managerFor()));
        assertThrows(NoUniqueBeanDefinitionException.class,
                () -> port.login(new AuthCredentials("alice", Store.PASSWORD)));
    }

    /** AB9：凭据原样、且仅一次递给认证链（参数写反的形态只有这一格能当场分出来）。 */
    @Test
    void should_hand_the_credentials_verbatim_to_the_manager_once() {
        AtomicReference<Authentication> received = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        AuthenticationManager recording = request -> {
            calls.incrementAndGet();
            received.set(request);
            return UsernamePasswordAuthenticationToken.authenticated(request.getName(), null,
                    List.of(new SimpleGrantedAuthority("order:read")));
        };
        SpringSecurityAuthPort port = port(managers(recording));
        port.login(new AuthCredentials("erin", "st4ssh"));
        assertEquals(1, calls.get());
        Authentication token = received.get();
        assertThat(token).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(token.getName()).isEqualTo("erin");
        assertThat(token.getCredentials()).isEqualTo("st4ssh");
        assertFalse(token.isAuthenticated(), "递给认证链的必须是未认证 token");
    }

    /** AB10：非法权限名在 holder 判定之前快速失败，无身份也抛。 */
    @Test
    void should_reject_a_blank_permission_before_looking_at_the_holder() {
        SpringSecurityAuthPort port = port(managers());
        assertThrows(IllegalArgumentException.class, () -> port.hasPermission(null));
        assertThrows(IllegalArgumentException.class, () -> port.hasPermission("   "));
        // 第三、四句钉的是"判权限不经 toAuthUser"（SpringSecurityAuthPort#hasPermission 的 javadoc 承诺）。
        this.scope.open(SecurityContextHolderScope.withPrincipal(null, List.of("order:read")));
        assertTrue(port.hasPermission("order:read"),
                "主体为 null 时 getName() 是空串（探针 50e）：改走 toAuthUser 的实现会在这里抛内核的空白身份校验，把一次权限判定变成 500");
    }

    /** AB11：零权限的已认证用户是合法形态——两个集合皆空、判权限 false 且不抛。 */
    @Test
    void should_report_a_user_without_any_authority_quietly() {
        this.scope.open(SecurityContextHolderScope.authenticated("frank", List.of()));
        SpringSecurityAuthPort port = port(managers());
        AuthUser user = port.currentUser().orElseThrow();
        assertThat(user.roles()).isEmpty();
        assertThat(user.permissions()).isEmpty();
        assertFalse(port.hasPermission("order:read"));
    }

    /** AB12：不缓存——换掉 holder 里的身份，两个方向的判定都立刻跟着变。 */
    @Test
    void should_read_the_authorities_fresh_on_every_call() {
        SpringSecurityAuthPort port = port(managers());
        this.scope.open(SecurityContextHolderScope.authenticated("gina", List.of("order:read")));
        assertTrue(port.hasPermission("order:read"));
        this.scope.open(SecurityContextHolderScope.authenticated("gina", List.of("order:write")));
        assertFalse(port.hasPermission("order:read"));
        assertTrue(port.hasPermission("order:write"));
    }

    /** AB13：匿名 token 在场时端口四方法全安静（RS2 那条判据在端口面的端到端形状）。 */
    @Test
    void should_stay_quiet_while_an_anonymous_token_is_installed() {
        this.scope.open(SecurityContextHolderScope.anonymous());
        SpringSecurityAuthPort port = port(managers());
        assertThat(port.currentUser()).isEmpty();
        assertFalse(port.hasPermission("ROLE_ANONYMOUS"));
        port.logout();
        assertThat(port.currentUser()).isEmpty();
    }
}
