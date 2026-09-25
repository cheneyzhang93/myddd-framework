package org.deep361.myddd.domain.autoconfigure;

import org.deep361.myddd.domain.context.DomainModule;
import org.deep361.myddd.domain.context.DomainModuleRegistry;
import org.deep361.myddd.domain.context.DomainModuleScanner;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * 领域装配自动配置：发现 {@code @Domain} 领域模块并冻结成 {@link DomainModuleRegistry}。
 *
 * <p>后续的事件分发（Task 13）、扩展点与规则装配（Task 15+）都以
 * {@code @AutoConfiguration(after = MydddDomainAutoConfiguration.class)} 挂在本配置之后。
 */
@AutoConfiguration
@EnableConfigurationProperties(MydddDomainProperties.class)
public class MydddDomainAutoConfiguration {

    /**
     * 领域模块注册表。
     *
     * <p>扫描根：{@code myddd.domain.scan-packages} 优先；未配置时回退到 Spring Boot 自动配置包；两者都没有
     * （非 Spring Boot 应用）或一个模块都没扫到，都直接抛异常让应用启动失败——starter 在 classpath 上却没有
     * 领域模块属于配置错误，不允许静默空转。
     */
    @Bean
    @ConditionalOnMissingBean
    public DomainModuleRegistry domainModuleRegistry(MydddDomainProperties properties, BeanFactory beanFactory) {
        List<String> basePackages = resolveBasePackages(properties, beanFactory);
        List<DomainModule> modules = new DomainModuleScanner(basePackages).scan();
        if (modules.isEmpty()) {
            throw new IllegalStateException("在扫描包 " + basePackages + " 下未发现任何 @Domain 领域模块；"
                    + "请修正 myddd.domain.scan-packages 配置，或移除 myddd-domain-starter 依赖");
        }
        return DomainModuleRegistry.of(modules);
    }

    private static List<String> resolveBasePackages(MydddDomainProperties properties, BeanFactory beanFactory) {
        List<String> configured = properties.getScanPackages();
        if (!configured.isEmpty()) {
            return configured;
        }
        if (!AutoConfigurationPackages.has(beanFactory)) {
            throw new IllegalStateException("未配置 myddd.domain.scan-packages，且当前上下文没有自动配置包"
                    + "（非 Spring Boot 应用？）；请显式配置 myddd.domain.scan-packages");
        }
        return AutoConfigurationPackages.get(beanFactory);
    }
}
