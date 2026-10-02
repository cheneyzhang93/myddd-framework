package org.deep361.myddd.auth.config;

import org.aspectj.lang.Aspects;
import org.deep361.myddd.auth.credential.CredentialVerifierGuard;
import org.deep361.myddd.auth.permission.PermissionCheckAspect;
import org.deep361.myddd.auth.permission.PermissionDeclarationValidator;
import org.deep361.myddd.authfixture.FakeAuthPort;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * auth 壳唯一一份自动装配的在场性判据（{@code AC1}–{@code AC8}）。
 *
 * <p>与 resilience 壳同一条夹具纪律：一律 {@code AutoConfigurations.of(...)} 点名注册，需要端口时用
 * {@code withBean} 挂替身，只断 bean 在场/不在场与属性通道真的接到构造参数上。本组<b>不断"注解生效"</b>
 * ——runner 里没有 {@code @EnableAspectJAutoProxy}，代理不建，织入面归 {@code AT}/{@code RS} 组。
 * "标了装配类却忘了登记 imports"这一格由同包的注册对账测试守住（{@code AC9}/{@code AC10}）。
 */
class MydddAuthAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddAuthAutoConfiguration.class));

    /** AC1：不写任何键 ⇒ 四个 bean 与属性类全在场；且框架从不自己开代理。 */
    @Test
    void should_produce_the_four_beans_and_never_declare_aspectj_proxy() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(MydddAuthProperties.class);
            assertThat(context).hasSingleBean(PermissionCheckAspect.class);
            assertThat(context).hasSingleBean(PermissionDeclarationValidator.class);
            assertThat(context).hasSingleBean(AuthPortGuard.class);
            assertThat(context).hasSingleBean(CredentialVerifierGuard.class);
            // 开不开代理是应用的决定；这一格不钉住，后来者"顺手让壳自己开代理"会覆盖应用的 proxyTargetClass。
            assertThat(MydddAuthAutoConfiguration.class
                    .getAnnotation(org.springframework.context.annotation.EnableAspectJAutoProxy.class)).isNull();
        });
    }

    /** AC2：两条退路（总开关关掉 / AspectJ 不在类路径）都不产出任何 bean。 */
    @Test
    void should_back_off_whole_assembly_when_disabled_or_aspectj_missing() {
        runner.withPropertyValues("myddd.auth.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PermissionCheckAspect.class);
            assertThat(context).doesNotHaveBean(PermissionDeclarationValidator.class);
            assertThat(context).doesNotHaveBean(AuthPortGuard.class);
            assertThat(context).doesNotHaveBean(CredentialVerifierGuard.class);
        });
        // 只跑上面那一条的话，@ConditionalOnClass 这一支没有杀手——两条钉的是同一件事（整份装配不被解析）。
        runner.withClassLoader(new FilteredClassLoader(Aspects.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PermissionCheckAspect.class);
            assertThat(context).doesNotHaveBean(AuthPortGuard.class);
            assertThat(context).doesNotHaveBean(CredentialVerifierGuard.class);
        });
    }

    /** AC3：关掉切面与校验器不许连带关掉两个守卫——守卫管端口在不在、有没有人校验凭据，与拦截无关。 */
    @Test
    void should_keep_guards_when_aspect_and_validator_are_switched_off() {
        runner.withPropertyValues("myddd.auth.permission-check.enabled=false",
                "myddd.auth.validate-declarations=false").run(context -> {
            assertThat(context).doesNotHaveBean(PermissionCheckAspect.class);
            assertThat(context).doesNotHaveBean(PermissionDeclarationValidator.class);
            assertThat(context).hasSingleBean(AuthPortGuard.class);
            assertThat(context).hasSingleBean(CredentialVerifierGuard.class);
        });
    }

    /** AC4：{@code verifier-required=false} 时零个凭据校验器也能起来（AC5 的同一条属性通道反向证据）。 */
    @Test
    void should_start_without_any_credential_verifier_when_not_required() {
        runner.withBean(AuthPort.class, FakeAuthPort::new)
                .withPropertyValues("myddd.auth.credential.verifier-required=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CredentialVerifierGuard.class);
                });
    }

    /** AC5：只关切面而校验器仍开着 ⇒ 启动失败，且话里点名那两个键与注解——证属性真递到了 {@code @PostConstruct}。 */
    @Test
    void should_fail_startup_when_aspect_is_off_but_declarations_are_still_validated() {
        runner.withPropertyValues("myddd.auth.permission-check.enabled=false").run(context -> {
            assertThat(context).hasFailed();
            // 实测（verifyAuth1.log）：这条失败在 bean 创建期发生，外层是 Spring 的
            // UnsatisfiedDependencyException 且消息不带我们的键名 ⇒ 判据只看根因（清单 A6 同一条）。
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(FrameworkException.class)
                    .rootCause()
                    .hasMessageContaining("myddd.auth.permission-check.enabled")
                    .hasMessageContaining("myddd.auth.validate-declarations")
                    .hasMessageContaining("@RequiresPermission");
        });
    }

    /** AC6：一个端口都不注册时上下文照样起来，诊断推迟到真调用的那一刻。 */
    @Test
    void should_diagnose_missing_port_at_call_time_not_at_startup() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AuthPortGuard.class);
            assertThatThrownBy(() -> context.getBean(AuthPortGuard.class).require())
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining(AuthPortGuard.PROVIDER_KEY)
                    .hasMessageContaining("myddd-auth-satoken-starter")
                    .hasMessageContaining("myddd-auth-security-starter");
        });
    }

    /** AC7：装配写错（构造收 {@code null}）要说人话，而不是在调用点上抛一个没有上下文的 NPE。 */
    @Test
    void should_reject_null_object_provider_at_construction() {
        assertThatThrownBy(() -> new AuthPortGuard(null))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("ObjectProvider<AuthPort>");
    }

    /**
     * AC8：零厂商依赖的正反两证。正向那一条是不可缺的对照——两条负向断言在"类路径整个坏了"时同样绿。
     * 同时钉住 {@code CredentialVerifier} 的落点：接口一旦被搬回壳的 {@code credential} 包，正向当场红。
     */
    @Test
    void should_keep_vendor_classes_off_the_classpath() throws ClassNotFoundException {
        assertThat(Class.forName("org.deep361.myddd.kernel.port.auth.CredentialVerifier")).isNotNull();
        assertThatThrownBy(() -> Class.forName("cn.dev33.satoken.stp.StpUtil"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("org.springframework.security.authentication.AuthenticationManager"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
