package org.deep361.myddd.auth.security.config;

import java.util.List;
import org.deep361.myddd.auth.security.SpringSecurityAuthPort;
import org.deep361.myddd.auth.security.testing.SecurityContextHolderScope;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.auth.AuthCredentials;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.deep361.myddd.kernel.port.auth.AuthUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 装配面（{@code AC1}–{@code AC5}）与 {@code RoleHierarchy} 在场的那一毫秒（{@code AC6}）。 */
class MydddAuthSecurityAutoConfigurationTest {

    private final SecurityContextHolderScope scope = new SecurityContextHolderScope();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddAuthSecurityAutoConfiguration.class));

    @AfterEach
    void closeTheHolder() {
        this.scope.close();
    }

    /** AC1：默认形态——端口是 security 的实现，装配面只贡献这一个 bean，代理照旧由应用决定。 */
    @Test
    void should_assemble_only_the_port_itself() {
        runner.withBean(AuthenticationManager.class, MydddAuthSecurityAutoConfigurationTest::passwordManager)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AuthPort.class);
                    assertThat(context.getBean(AuthPort.class)).isInstanceOf(SpringSecurityAuthPort.class);
                    assertThat(context).hasSingleBean(MydddAuthSecurityProperties.class);
                    assertThat(MydddAuthSecurityAutoConfiguration.class.getAnnotation(EnableAspectJAutoProxy.class))
                            .isNull();
                });
    }

    /** AC2：provider 自己的开关与二选一判定各关掉同一份装配（两键两条机制，结论同一条）。 */
    @Test
    void should_stand_down_when_disabled_or_when_another_provider_is_selected() {
        runner.withPropertyValues("myddd.auth.security.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(AuthPort.class);
            assertThat(context).doesNotHaveBean(MydddAuthSecurityProperties.class);
        });
        runner.withPropertyValues("myddd.auth.provider=satoken").run(context -> {
            assertThat(context).doesNotHaveBean(AuthPort.class);
            assertThat(context).doesNotHaveBean(MydddAuthSecurityProperties.class);
        });
    }

    /** AC3：两条认证链且其中一条 {@code @Primary} ⇒ 端口吃被指认那一条（歧义交给 Spring 裁决）。 */
    @Test
    void should_honour_a_primary_authentication_manager() {
        runner.withUserConfiguration(TwoManagers.class).run(context -> {
            assertThat(context).hasNotFailed();
            AuthPort port = context.getBean(AuthPort.class);
            port.login(new AuthCredentials("50203", "primary-secret"));
            assertEquals("50203", port.currentUser().orElseThrow().userId());
            // 主链只认 primary-secret；若端口连的是次链，上面那次登录必然抛。
            assertFalse(port.hasPermission("order:write"));
            assertTrue(port.hasPermission("order:read"));
        });
    }

    /** AC4：没有认证链 ⇒ 装配不失败、读面不受影响，只有登录那一次才报，且给出两条出路。 */
    @Test
    void should_assemble_without_an_authentication_manager_and_fail_only_at_login_time() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AuthPort.class);
            AuthPort port = context.getBean(AuthPort.class);
            this.scope.open(SecurityContextHolderScope.authenticated("50204", List.of("order:read")));
            assertThat(port.currentUser()).map(AuthUser::userId).contains("50204");
            FrameworkException failure = assertThrows(FrameworkException.class,
                    () -> port.login(new AuthCredentials("50204", "whatever")));
            assertThat(failure.getMessage())
                    .contains("AuthenticationManager", "myddd.auth.provider", "satoken");
            assertThat(port.currentUser()).map(AuthUser::userId).contains("50204");
            assertTrue(port.hasPermission("order:read"));
        });
    }

    /** AC5：本装配只贡献端口实现，<b>不</b>贡献任何厂商协作者。 */
    @Test
    void should_never_produce_a_vendor_authentication_collaborator() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AuthPort.class);
            assertThat(context).doesNotHaveBean(AuthenticationManager.class);
            assertThat(context).doesNotHaveBean(AuthenticationProvider.class);
            assertThat(context).doesNotHaveBean(UserDetailsService.class);
            assertThat(context).doesNotHaveBean(PasswordEncoder.class);
        });
    }

    /** AC6：{@code RoleHierarchy} 在场——端口不跟随、厂商跟随、装配不失败（D-196 那一毫秒的容器级读数）。 */
    @Test
    void should_ignore_a_role_hierarchy_that_the_vendor_follows() {
        Authentication authenticated = SecurityContextHolderScope.authenticated(
                "50205", List.of("ROLE_admin", "order:read"));
        this.scope.open(authenticated);
        runner.withBean(RoleHierarchy.class,
                () -> RoleHierarchyImpl.fromHierarchy("ROLE_admin > ROLE_user")).run(context -> {
            assertThat(context).hasNotFailed();
            SpringSecurityAuthPort port = (SpringSecurityAuthPort) context.getBean(AuthPort.class);
            assertThat(port.currentUser().orElseThrow().roles()).containsExactly("admin");
            assertFalse(port.hasPermission("user"), "端口只做精确匹配，不展开角色层级");
            AuthorityAuthorizationManager<String> vendor = AuthorityAuthorizationManager.hasRole("user");
            vendor.setRoleHierarchy(context.getBean(RoleHierarchy.class));
            assertTrue(vendor.authorize(() -> authenticated, null).isGranted(),
                    "同一份认证、同一个层级：厂商判 true（这一格与上一格必须同时成立）");
        });
    }

    /**
     * 只认 {@code 50203/primary-secret} 那一条链的夹具。认不上就<b>抛</b>而不是返回一个未认证 token：
     * 厂商侧的语义就是抛（{@code 50a} 第 1 条），夹具跟着厂商形状走，{@code AC3} 的失败读数才落在"连错链"
     * 而不是落在"链返回了个奇怪的东西"上。
     */
    private static AuthenticationManager passwordManager() {
        return request -> {
            if (!"50203".equals(request.getName()) || !"primary-secret".equals(request.getCredentials())) {
                throw new BadCredentialsException("not the primary chain");
            }
            return UsernamePasswordAuthenticationToken.authenticated(request.getName(), null,
                    List.of(new SimpleGrantedAuthority("order:read")));
        };
    }

    /** AC3 的夹具：两条链，被指认的那一条才是 {@link #passwordManager()}。 */
    @Configuration(proxyBeanMethods = false)
    static class TwoManagers {

        @Bean
        @Primary
        AuthenticationManager primaryAuthenticationManager() {
            return passwordManager();
        }

        /** 次链：任何凭据都抛 ⇒ 端口若连到它，{@code AC3} 那次登录当场红。 */
        @Bean
        AuthenticationManager secondaryAuthenticationManager() {
            return request -> {
                throw new BadCredentialsException("not the primary");
            };
        }
    }
}
