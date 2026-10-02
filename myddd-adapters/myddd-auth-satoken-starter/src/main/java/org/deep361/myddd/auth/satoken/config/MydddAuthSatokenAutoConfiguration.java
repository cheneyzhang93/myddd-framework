package org.deep361.myddd.auth.satoken.config;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import org.deep361.myddd.auth.satoken.SaTokenAuthPort;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.deep361.myddd.kernel.port.auth.CredentialVerifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/**
 * satoken provider 的唯一一份自动装配：两个 bean——{@link AuthPort} 的实现与 R15 自检。
 *
 * <p><b>三个注解各管一件事，与 limiter provider（{@code 46i}）逐字同形</b>：
 * {@code @ConditionalOnClass(name = "cn.dev33.satoken.stp.StpUtil")} 管"厂商在不在"，
 * {@code @Conditional(MydddAuthSatokenProviderCondition.class)} 同时管 {@code myddd.auth.satoken.enabled}
 * 与 {@code myddd.auth.provider} 两键（{@code @ConditionalOnProperty} 不是 {@code @Repeatable}，
 * 两条注解叠不上，Plan 2 记过一次编译失败）。{@code name =} 用<b>字符串</b>：类引用形式在装配条件求值前
 * 就会加载 {@code StpUtil}，等于把这条条件判死。⚠️ 这一格与 {@code 45l} 第 5 条同为"改坏也不改变装配结论"
 * 的不可观察面（本模块直接依赖 {@code sa-token-core}，离线环境里没法把它移走再跑），按缺口登记、
 * 不写复述式用例（{@code 49i} 的"必须写清的事"第 3 条）。
 *
 * <p><b>不声明 {@code @EnableAspectJAutoProxy}</b>：开不开代理是应用的决定（一手沿用
 * {@code MydddConsistencyLockAopAutoConfigurationTest:57} 的反射断法），而且本模块连切面都没有——
 * 拦截发生在壳里（Task 48）。
 *
 * <p><b>bean 方法不写 {@code @ConditionalOnMissingBean}</b>（{@code 46i} 那六个 bean 都写了，因为它们
 * 是替换点；这里不是）：{@code AuthPort} 的替换点由 {@code myddd.auth.provider} 决定，业务想包一层
 * 就在自己的实现上打 {@code @Primary}（{@code SaTokenAuthPort} 的校验器消费点同样吃 {@code @Primary}），
 * 而我们静默让位的后果是"没人知道自己换成的是哪一份"——零个端口与两个端口都由壳的
 * {@code AuthPortGuard} 报出来（Task 48），比这里安静地消失可读得多。
 *
 * <p><b>bean 名带 provider 段</b>（{@code mydddSatokenAuthPort}，Task 50 侧是
 * {@code mydddSecurityAuthPort}）：万一两份装配同时生效（条件被改坏），两个不同名字会产出两个
 * {@code AuthPort} ⇒ 壳的守卫当场报"多个"；同名则后者静默覆盖前者，那是一条谁都看不见的替换。
 */
@AutoConfiguration
@ConditionalOnClass(name = "cn.dev33.satoken.stp.StpUtil")
@Conditional(MydddAuthSatokenProviderCondition.class)
@EnableConfigurationProperties(MydddAuthSatokenProperties.class)
public class MydddAuthSatokenAutoConfiguration {

    @Bean
    AuthPort mydddSatokenAuthPort(ObjectProvider<CredentialVerifier> credentialVerifiers) {
        return new SaTokenAuthPort(credentialVerifiers);
    }

    @Bean
    StpInterfacePresenceGuard mydddStpInterfacePresenceGuard(ObjectProvider<StpInterface> sources) {
        return new StpInterfacePresenceGuard(sources);
    }
}
