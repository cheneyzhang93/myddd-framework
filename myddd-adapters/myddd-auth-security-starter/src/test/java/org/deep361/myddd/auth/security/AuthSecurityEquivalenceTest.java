package org.deep361.myddd.auth.security;

import org.deep361.myddd.auth.security.config.AuthSecurityProviderSelection;
import org.deep361.myddd.auth.security.testing.SecurityContextHolderScope;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.auth.AuthCredentials;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.deep361.myddd.kernel.port.auth.AuthUser;
import org.deep361.myddd.testing.contract.AuthProviderEquivalenceTestBase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AuthProviderEquivalenceTestBase} 的 Spring Security 侧子类：八行全表达（{@code EQ7} 在本侧
 * 是"端口不跟随层级"那一格，satoken 侧由基类按 {@code NOT_EXPRESSIBLE} 跳过）。
 *
 * <p><b>身份四格只有一个来源</b>（清单 {@code A2}）：{@code userId} 与 {@code username} 同取
 * {@code Authentication#getName()}，{@code roles} 是按 {@code ROLE_} 前缀剥出来的<b>叠加</b>视图，
 * {@code permissions} 收全部权限串（含带前缀那一条）。所以 {@link #KNOWN} 的权限集里有
 * {@code ROLE_finance}——照 {@code knownUser()} 的字面形状写会少一条，{@code EQ1} 当场红。
 *
 * <p><b>认证链夹具是自己写的 {@code AuthenticationManager}</b>：口令错抛
 * {@link BadCredentialsException}（⇒ 端口翻译成 401），绝不抛
 * {@code InternalAuthenticationServiceException}（那是"用户库故障"那一支，翻译成 500，本类不测它，
 * {@code verify} 包的用例钉）。认证成功的返回值就是 holder 里最终躺着的 token，所以
 * {@code login → currentUser} 这一条不经任何夹具回填。
 */
class AuthSecurityEquivalenceTest extends AuthProviderEquivalenceTestBase {

    private static final String PASSWORD = "correct-password";
    /** 本家只有一个身份字符串：登录名与用户 id 同源，没有 satoken 那样的第二格（清单 {@code A2}）。 */
    private static final String NAME = "cheney";
    private static final List<String> AUTHORITIES = List.of("ROLE_finance", "order:read");
    private static final AuthUser KNOWN = new AuthUser(NAME, NAME,
            Set.of("finance"), Set.of("ROLE_finance", "order:read"));

    private final SecurityContextHolderScope scope = new SecurityContextHolderScope();
    /** 最近一次 {@link #grant} 装进去的授予，{@link #grantHierarchy()} 在它之上叠加。 */
    private List<String> granted = List.of();

    @Override
    protected String providerTag() {
        return "security";
    }

    @Override
    protected AuthPort newPort() {
        AuthenticationManager manager = new AuthenticationManager() {
            @Override
            public Authentication authenticate(Authentication request) throws AuthenticationException {
                boolean rightUser = NAME.equals(request.getName());
                boolean rightPassword = PASSWORD.equals(request.getCredentials());
                if (!rightUser || !rightPassword) {
                    throw new BadCredentialsException("凭据不通过");
                }
                return SecurityContextHolderScope.authenticated(NAME, AUTHORITIES);
            }
        };
        ObjectProvider<AuthenticationManager> managers = new ObjectProvider<>() {
            @Override
            public Stream<AuthenticationManager> stream() {
                return Stream.of(manager);
            }
        };
        return new SpringSecurityAuthPort(managers);
    }

    @Override
    protected AuthUser knownUser() {
        return KNOWN;
    }

    @Override
    protected AuthCredentials knownCredentials() {
        return new AuthCredentials(NAME, PASSWORD);
    }

    @Override
    protected AuthCredentials brokenCredentials() {
        return new AuthCredentials(NAME, "wrong-" + PASSWORD);
    }

    /** "有作用域而无身份"在本家就是厂商匿名 token：{@code isAuthenticated()} 为 true 而身份为空。 */
    @Override
    protected void openRequestScope() {
        granted = AUTHORITIES;
        scope.open(SecurityContextHolderScope.anonymous());
    }

    @Override
    protected void closeRequestScope() {
        scope.close();
        granted = List.of();
    }

    @Override
    protected void grant(String... permissions) {
        granted = List.of(permissions);
        scope.open(SecurityContextHolderScope.authenticated(NAME, granted));
    }

    /**
     * 表 {@code EQ7} 在本侧的搭法：给一条层级会展开成 {@code user:read} 的角色权限串。端口的读数
     * 必须仍是"没有这条权限"——层级只在厂商的 {@code AuthorizationManager} 那一侧生效（本模块 {@code AC6}
     * 钉厂商生效那一格，清单 {@code A3} 说明端口为什么不跟随）。
     */
    @Override
    protected void grantHierarchy() {
        List<String> mixed = new ArrayList<>(granted);
        mixed.add("ROLE_admin");
        scope.open(SecurityContextHolderScope.authenticated(NAME, mixed));
    }

    @Override
    protected String selectionFailureMessage(Set<String> present) {
        FrameworkException thrown = assertThrows(FrameworkException.class,
                () -> AuthSecurityProviderSelection.shouldAssembleSecurity(null, present));
        return thrown.getMessage();
    }
}
