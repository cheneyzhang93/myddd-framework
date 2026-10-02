package org.deep361.myddd.cache.autoconfigure;

import org.deep361.myddd.cache.composite.TwoLevelCacheManager;
import org.deep361.myddd.cache.l1.CaffeineLevelFactory;
import org.deep361.myddd.cache.l2.RedisLevelFactory;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 缓存适配层的自动装配：{@code myddd.cache.type} 决定产出哪一种 {@link CacheManager}。
 *
 * <p><b>两个分支互斥且合起来覆盖全部取值</b>：{@code caffeine} 只有进程内一层，{@code multi}（缺省）
 * 是 Caffeine L1 + Redis L2 的两级组合。互斥由 {@link ConditionalOnProperty} 保证；缺省走 {@code multi}
 * 靠 {@code matchIfMissing = true}，它与 {@link MydddCacheProperties.Type#MULTI} 这个字段初值必须同步改，
 * 因此 {@code MydddCachePropertiesTest} 里有一条用例同时钉两边（缺省值与装配结果）。
 *
 * <p><b>两级管理器的两层不是 bean</b>：L1 与 L2 各自的管理器在 {@link #twoLevelCacheManager} 内部构造，
 * 容器里因此只有<b>一个</b> {@code CacheManager}。注册成三个 bean 的话，{@code @Cacheable} 的按类型注入
 * 要靠 {@code @Primary} 才不歧义，而业务的 {@code @ConditionalOnMissingBean(CacheManager.class)}
 * 也会因为我们那两个内部层而误判"已经有管理器了"。
 *
 * <p><b>为什么排在 Boot 的 {@code CacheAutoConfiguration} 之前</b>：Boot 那个装配类自己也会造一个
 * {@code CacheManager}（按 classpath 挑 Simple/Caffeine/Redis…），谁先注册谁占住 {@code CacheManager}
 * 这个类型。用 {@code beforeName} 按<b>名字</b>声明先后而不是 {@code before} 按类声明，是因为 Boot 4 把
 * 缓存自动装配拆进了独立的 {@code spring-boot-cache} jar：按类引用会把一个我们不用的 jar 变成编译期依赖，
 * 而按名字引用在目标类缺席时只是排序为空，不会 {@code NoClassDefFoundError}。
 *
 * <p><b>不接管 {@code @EnableCaching}</b>：开不开缓存代理是业务工程的决定（决定哪些应用启用），
 * 框架只保证"你开了，拿到的就是按 {@code myddd.cache.*} 配出来的管理器"。
 *
 * <p><b>业务自带 {@code CacheManager} 时框架让位</b>（{@link ConditionalOnMissingBean}）：与持久化侧
 * Outbox 两个装配类同一条纪律。让位不等于失声——{@link MydddCacheProperties#validate()} 挂在配置 bean 的
 * {@code @PostConstruct} 上，与这里是否产出 bean 无关，所以"配了 {@code max-size=0} 又自带管理器"
 * 依然会在启动期失败，而不是留下一个永不命中的缓存。
 *
 * <p><b>两级缓存没有失效广播</b>：别的实例改了 L2，本实例的 L1 最长还会用
 * {@code myddd.cache.caffeine.expire-after-write} 这么久。这是本能力的交付语义（不是待办），
 * 键表里也没有为失效总线预留任何键；需要更强一致时把 L1 的过期调短。
 */
@AutoConfiguration(beforeName = "org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration")
@ConditionalOnProperty(prefix = "myddd.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MydddCacheProperties.class)
public class MydddCacheAutoConfiguration {

    /** 只有 L1 的分支：给不接 Redis 的进程用，因此不需要 {@code RedisConnectionFactory} bean。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "myddd.cache", name = "type", havingValue = "caffeine")
    static class CaffeineOnlyConfiguration {

        @Bean
        @ConditionalOnMissingBean(CacheManager.class)
        CaffeineCacheManager caffeineCacheManager(MydddCacheProperties properties) {
            return CaffeineLevelFactory.create(properties);
        }
    }

    /** 缺省分支：Caffeine L1 + Redis L2。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "myddd.cache", name = "type", havingValue = "multi",
            matchIfMissing = true)
    static class TwoLevelConfiguration {

        @Bean
        @ConditionalOnMissingBean(CacheManager.class)
        TwoLevelCacheManager twoLevelCacheManager(MydddCacheProperties properties,
                ObjectProvider<RedisConnectionFactory> connectionFactory) {
            // 用 ObjectProvider 而不是直接注入：为的是把"缺连接"这件事说成一句能照着做的话，
            // 而不是 UnsatisfiedDependencyException 里一串 bean 名。
            RedisConnectionFactory factory = connectionFactory.getIfAvailable();
            if (factory == null) {
                throw new FrameworkException("myddd.cache.type=multi（缺省值）需要一个 RedisConnectionFactory bean，"
                        + "容器里没有它。要么引入 Boot 的 redis 自动装配并配 spring.data.redis.host 等键"
                        + "（测试里用 myddd-test-starter 的 MydddRedisContainer），"
                        + "要么显式要只有进程内缓存：myddd.cache.type=caffeine");
            }
            return new TwoLevelCacheManager(CaffeineLevelFactory.create(properties),
                    RedisLevelFactory.create(factory, properties), properties.isCacheNullValues());
        }
    }
}
