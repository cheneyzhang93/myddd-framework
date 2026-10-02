package org.deep361.myddd.auth.satoken.config;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpInterfaceDefaultImpl;
import org.deep361.myddd.auth.satoken.SaTokenAuthPort;
import org.deep361.myddd.auth.satoken.testing.SaTokenRequestScope;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.auth.AuthCredentials;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.deep361.myddd.kernel.port.auth.AuthUser;
import org.deep361.myddd.kernel.port.auth.CredentialVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 装配面（{@code AC1}–{@code AC3}）与 R15 自检三档加一面镜子（{@code AC4}–{@code AC7}）。 */
class MydddAuthSatokenAutoConfigurationTest {

    /** AC5/AC6/AC7 各用不同<b>具名</b>实例：{@code SaManager} 的权限源是进程级静态，共用会互相污染；而匿名实现两条都同名，断言就失去分辨力（见下两个类）。 */
    private static final StpInterface WITNESSED = new WitnessSource();
    private static final StpInterface ORPHANED = new OrphanSource();

    private final SaTokenRequestScope scope = new SaTokenRequestScope();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddAuthSatokenAutoConfiguration.class));

    @AfterEach
    void closeScopeAndRestoreThePermissionSource() {
        scope.close();
        SaTokenRequestScope.restoreDefaultPermissionSource();
    }

    /** AC1：默认形态——端口是 SaToken 的实现，权限源只有业务那一个（本模块不产），且框架不开代理。 */
    @Test
    void should_assemble_the_port_and_never_a_permission_source_of_our_own() {
        runner.withBean(StpInterface.class, () -> installed(WITNESSED)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AuthPort.class);
            assertThat(context.getBean(AuthPort.class)).isInstanceOf(SaTokenAuthPort.class);
            assertThat(context).hasSingleBean(StpInterfacePresenceGuard.class);
            assertThat(context).hasSingleBean(MydddAuthSatokenProperties.class);
            assertThat(context).hasSingleBean(StpInterface.class);
            assertThat(context.getBean(StpInterface.class)).isSameAs(WITNESSED);
            assertThat(MydddAuthSatokenAutoConfiguration.class.getAnnotation(EnableAspectJAutoProxy.class))
                    .isNull();
        });
    }

    /** AC2：provider 自己的开关与二选一判定各关掉同一份装配（两键两条机制，结论同一条）。 */
    @Test
    void should_stand_down_when_disabled_or_when_another_provider_is_selected() {
        runner.withPropertyValues("myddd.auth.satoken.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(AuthPort.class);
            assertThat(context).doesNotHaveBean(StpInterfacePresenceGuard.class);
            assertThat(context).doesNotHaveBean(MydddAuthSatokenProperties.class);
        });
        runner.withPropertyValues("myddd.auth.provider=security").run(context -> {
            assertThat(context).doesNotHaveBean(AuthPort.class);
            assertThat(context).doesNotHaveBean(StpInterfacePresenceGuard.class);
        });
    }

    /** AC3：两个校验器且其中一个是 {@code @Primary} ⇒ 端口吃被指认的那一个（歧义交给 Spring 裁决，框架不自己数）。 */
    @Test
    void should_honour_a_primary_credential_verifier() {
        scope.open();
        SaTokenRequestScope.installByUser(Map.of("49303", List.of("order:read")), Map.of("49303", List.of("finance")));
        runner.withBean(StpInterface.class, () -> installed(WITNESSED))
                .withUserConfiguration(TwoVerifiers.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AuthPort port = context.getBean(AuthPort.class);
                    port.login(new AuthCredentials("cheney", "correct-password"));
                    AuthUser user = port.currentUser().orElseThrow();
                    assertEquals("49303", user.userId());
                    assertThat(user.permissions()).containsExactly("order:read");
                });
    }

    /** AC4：一个权限源都没有 ⇒ 启动失败，消息必须同时给出"后果"与"出路"。 */
    @Test
    void should_fail_fast_when_no_permission_source_is_declared() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).isInstanceOf(FrameworkException.class)
                    .hasMessageContaining(StpInterface.class.getName())
                    .hasMessageContaining(StpInterfaceDefaultImpl.class.getName())
                    .hasMessageContaining("sa-token-spring-boot4-starter")
                    .hasMessageContaining("静默");
        });
    }

    /** AC5：恰好一个，且 {@code SaManager} 用的就是它 ⇒ 放行（这条绿是 AC6 那条红不是恒真的唯一证据）。 */
    @Test
    void should_start_when_the_declared_bean_is_the_one_sa_token_uses() {
        runner.withBean(StpInterface.class, () -> installed(WITNESSED)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(StpInterfacePresenceGuard.class);
        });
    }

    /** AC6：bean 在场但 {@code SaManager} 认的是别人——业务声明了 {@code @Bean} 却没引 starter 的真实形态。 */
    @Test
    void should_fail_fast_when_sa_token_is_not_using_the_declared_bean() {
        SaTokenRequestScope.install(ORPHANED);
        runner.withBean(StpInterface.class, () -> WITNESSED).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).isInstanceOf(FrameworkException.class)
                    .hasMessageContaining(WITNESSED.getClass().getName())
                    .hasMessageContaining(ORPHANED.getClass().getName())
                    .hasMessageContaining("sa-token-spring-boot4-starter");
        });
    }

    /** AC7：两个权限源 ⇒ 启动失败，报数量与两个类名（不猜）。 */
    @Test
    void should_refuse_to_guess_between_two_permission_sources() {
        runner.withBean("first", StpInterface.class, () -> WITNESSED)
                .withBean("second", StpInterface.class, () -> ORPHANED)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(FrameworkException.class)
                            .hasMessageContaining("2")
                            .hasMessageContaining(WITNESSED.getClass().getName())
                            .hasMessageContaining(ORPHANED.getClass().getName());
                });
    }

    /** 模拟 {@code sa-token-spring-boot4-starter} 的 {@code SaBeanInject} 那一句：把容器里的 bean 递给 {@code SaManager}。 */
    private static StpInterface installed(StpInterface source) {
        SaTokenRequestScope.install(source);
        return source;
    }

    /** AC3 的夹具：两个校验器，被指认的那一个给 49303。 */
    @Configuration(proxyBeanMethods = false)
    static class TwoVerifiers {

        @Bean
        @Primary
        CredentialVerifier primaryVerifier() {
            return credentials -> new AuthUser("49303", "cheney", Set.of("finance"), Set.of("order:read"));
        }

        @Bean
        CredentialVerifier secondaryVerifier() {
            return credentials -> new AuthUser("49903", "someone-else", Set.of(), Set.of());
        }
    }

    /** 两个<b>具名</b>权限源实现：类名要能被诊断消息分辨出来，匿名类会同名。 */
    private static final class WitnessSource implements StpInterface {

        @Override
        public List<String> getPermissionList(Object loginId, String loginType) {
            return List.of("order:read");
        }

        @Override
        public List<String> getRoleList(Object loginId, String loginType) {
            return List.of("finance");
        }
    }

    private static final class OrphanSource implements StpInterface {

        @Override
        public List<String> getPermissionList(Object loginId, String loginType) {
            return List.of();
        }

        @Override
        public List<String> getRoleList(Object loginId, String loginType) {
            return List.of();
        }
    }
}
