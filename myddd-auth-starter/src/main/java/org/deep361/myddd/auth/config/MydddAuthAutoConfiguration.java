package org.deep361.myddd.auth.config;

import org.aspectj.lang.Aspects;
import org.deep361.myddd.auth.credential.CredentialVerifierGuard;
import org.deep361.myddd.auth.permission.PermissionCheckAspect;
import org.deep361.myddd.auth.permission.PermissionDeclarationValidator;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.deep361.myddd.kernel.port.auth.CredentialVerifier;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * auth 壳的唯一一份自动装配：交出<b>四个 bean</b>，别的一个都不产。
 *
 * <p><b>条件与 resilience 壳（Task 44）逐字同形</b>：{@code @ConditionalOnClass(Aspects.class)} +
 * 总开关 {@code myddd.auth.enabled}（缺省开）。<b>不</b>写 {@code @ConditionalOnBean(AuthPort.class)}：
 * 端口有没有在调用时由 {@link AuthPortGuard} 给诊断（{@code AC6}），启动期靠
 * {@code @ConditionalOnBean} 猜装配顺序在 {@code ApplicationContextRunner} 下测不到（Plan 3 的同一课，
 * 也是 Task 44 的 {@code AC6} 用过的写法）。也<b>不</b>写 {@code @ConditionalOnMissingBean}：
 * 切面不是替换点，替换点是 {@code AuthPort} 本身；而<b>本类一个 {@code AuthPort} 都不产</b>。
 * 框架一律不声明 {@code @EnableAspectJAutoProxy}——开不开代理是应用的决定
 * （一手：{@code MydddConsistencyLockAopAutoConfigurationTest:57}），{@code AC1} 反射读注解钉这一格。
 *
 * <p><b>四个 bean 的开关分工</b>：切面归 {@code myddd.auth.permission-check.enabled}，校验器归
 * {@code myddd.auth.validate-declarations}，两个守卫没有自己的开关（{@code myddd.auth.enabled=false}
 * 时整份装配不解析）。这条分工是 {@code AC3} 的判据：关掉切面不许连带关掉守卫，因为守卫管的是
 * "端口在不在、有没有人校验凭据"，那两件事与拦截无关。
 *
 * <p><b>为什么守卫也要在切面关掉时继续在场</b>：{@code permission-check.enabled=false} 之后
 * {@code AuthPort} 依然可能被业务直接调用（{@code currentUser()}/{@code login()}），那时"零个端口"与
 * "没人校验凭据"仍然是两条需要成话的启动期事实；把它们跟着切面一起关掉，等于让关掉拦截的应用
 * 顺带关掉诊断，那是最不容易被发现的一种连带。
 *
 * <p><b>{@code myddd.auth.credential.verifier-required} 不在这里判</b>：本类只看总开关与切面/校验器两个
 * 开关，那一键由 {@link CredentialVerifierGuard} 自己在 {@code afterSingletonsInstantiated()} 里读
 * （{@code AC4}）。两个 {@code @ConditionalOnProperty} 不许叠在同一个元素上（一手实测：它不是
 * {@code @Repeatable}，Plan 2 记过一次编译失败），所以逐方法各挂一条而不是在类上叠两条。
 */
@AutoConfiguration
@ConditionalOnClass(Aspects.class)
@EnableConfigurationProperties(MydddAuthProperties.class)
@ConditionalOnProperty(prefix = "myddd.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MydddAuthAutoConfiguration {

    @Bean
    AuthPortGuard mydddAuthPortGuard(ObjectProvider<AuthPort> ports) {
        return new AuthPortGuard(ports);
    }

    @Bean
    @ConditionalOnProperty(prefix = "myddd.auth.permission-check", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    PermissionCheckAspect mydddPermissionCheckAspect(AuthPortGuard guard) {
        return new PermissionCheckAspect(guard);
    }

    @Bean
    @ConditionalOnProperty(prefix = "myddd.auth", name = "validate-declarations", havingValue = "true",
            matchIfMissing = true)
    PermissionDeclarationValidator mydddPermissionDeclarationValidator(ListableBeanFactory beans,
            MydddAuthProperties properties) {
        return new PermissionDeclarationValidator(beans, properties);
    }

    @Bean
    CredentialVerifierGuard mydddCredentialVerifierGuard(ObjectProvider<CredentialVerifier> verifiers,
            ObjectProvider<AuthPort> ports, MydddAuthProperties properties) {
        return new CredentialVerifierGuard(verifiers, ports, properties);
    }
}
