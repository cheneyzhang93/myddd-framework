package org.deep361.myddd.auth.security.config;

import org.deep361.myddd.auth.security.SpringSecurityAuthPort;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.authentication.AuthenticationManager;

/**
 * security provider 的唯一一份自动装配：<b>只产一个 bean</b>——{@link AuthPort} 的实现。
 *
 * <p><b>三个注解各管一件事，与 satoken 侧逐字同形</b>：
 * {@code @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")}
 * 管"厂商在不在"，{@code @Conditional(MydddAuthSecurityProviderCondition.class)} 同时管
 * {@code myddd.auth.security.enabled} 与 {@code myddd.auth.provider} 两键（{@code @ConditionalOnProperty}
 * 不是 {@code @Repeatable}，两条注解叠不上）。{@code name =} 用<b>字符串</b>：类引用形式在装配条件求值前
 * 就会加载 {@code SecurityContextHolder}，等于把这条条件判死。
 *
 * <p><b>与 satoken 侧的唯一实质差：这里没有第二个 bean。</b>那边多出的是一份权限源自检守卫，因为
 * Sa-Token 侧"没人接权限源"的形态是<b>静默全 deny</b>；这一家不存在同形的静默洞（没有
 * {@code AuthenticationManager} 时登录根本无从调用），完整理由写在 {@code verify} 包说明第一段，
 * 容器级的反向证据是 {@code AC4}——它断的是"装配<b>不</b>失败"。
 *
 * <p><b>不声明 {@code @EnableAspectJAutoProxy}</b>：开不开代理是应用的决定，而且本模块连切面都没有——
 * 拦截发生在壳里。
 *
 * <p><b>bean 方法不写 {@code @ConditionalOnMissingBean}</b>：{@code AuthPort} 的替换点由
 * {@code myddd.auth.provider} 决定，业务想包一层就在自己的实现上打 {@code @Primary}，而我们静默让位的
 * 后果是"没人知道自己换成的是哪一份"——零个端口与两个端口都由壳的守卫报出来，比这里安静地消失可读得多。
 *
 * <p><b>bean 名带 provider 段</b>（{@code mydddAuthSecurityAuthPort}，与 satoken 侧的
 * {@code mydddSatokenAuthPort} 对称）：万一两份装配同时生效（条件被改坏），两个不同名字会产出两个
 * {@code AuthPort} ⇒ 壳的守卫当场报"多个"；同名则后者静默覆盖前者，那是一条谁都看不见的替换。
 * 这一格的<b>容器级</b>判据（两家同时在 classpath 上的读数）欠跨家等价性轮。
 *
 * <p><b>本装配不产任何厂商协作者</b>：没有 {@code AuthenticationManager}、没有
 * {@code AuthenticationProvider}、没有 {@code UserDetailsService}、没有 {@code PasswordEncoder}——
 * 那四份是"用户库长什么样、口令怎么存"的决定，归业务（或由业务引的 {@code spring-boot-starter-security}
 * 提供），框架产一份就是替全站决定认证因子。判据是 {@code AC5}。
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
@Conditional(MydddAuthSecurityProviderCondition.class)
@EnableConfigurationProperties(MydddAuthSecurityProperties.class)
public class MydddAuthSecurityAutoConfiguration {

    @Bean
    AuthPort mydddAuthSecurityAuthPort(ObjectProvider<AuthenticationManager> authenticationManagers) {
        return new SpringSecurityAuthPort(authenticationManagers);
    }
}
