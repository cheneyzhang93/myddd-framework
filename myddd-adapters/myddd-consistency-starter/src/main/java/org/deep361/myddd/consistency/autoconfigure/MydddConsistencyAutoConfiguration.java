package org.deep361.myddd.consistency.autoconfigure;

import org.deep361.myddd.consistency.lock.DefaultLockPort;
import org.deep361.myddd.consistency.lock.RedissonClientFactory;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.consistency.LockPort;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 一致性适配层的自动装配：锁能力（{@link LockPort}）建在 Redisson 上。
 *
 * <p><b>连接参数不在这类里</b>：{@code spring.data.redis.*} 由 Boot 解析成
 * {@link DataRedisConnectionDetails}，我们只消费那份视图（见 {@link RedissonClientFactory}）。
 * 用 {@link ObjectProvider} 而不是直接注入，为的是把"缺连接参数"说成一句能照着做的话，
 * 而不是 {@code UnsatisfiedDependencyException} 里一串 bean 名。
 *
 * <p><b>不声明自动配置顺序</b>：{@code #mydddRedissonClient} 的方法参数就是
 * {@link DataRedisConnectionDetails}，容器因此天然保证"它的 bean 先于我们创建"。
 * 在这种情况下再加 {@code afterName} 是一条<b>测不到</b>的声明——{@code ApplicationContextRunner}
 * 不读顺序元数据，唯一能杀它的用例会退化成正则复述（缓存适配器 Task 23 的变异 M50 实测的正是这件事：
 * 一个看不见又无法取证排序的注解属性，改动后 0 条测试变红）。能观察的顺序承诺才值得写。
 *
 * <p><b>业务自带 {@code RedissonClient} 或 {@code LockPort} 时框架让位</b>（{@link ConditionalOnMissingBean}
 * 的一个条件里给这两个类型）：与缓存/持久化侧同一条纪律——尤其"自带锁实现就不再建客户端"，
 * 因为客户端在锁这条链上唯一的用户就是 {@link DefaultLockPort}。让位不等于失声——
 * {@link MydddConsistencyProperties#validate()} 挂在配置 bean 的 {@code @PostConstruct} 上，
 * 与这里是否产出 bean 无关，所以"配了空白前缀又自带客户端"依然在启动期失败。
 *
 * <p><b>{@code lock.enabled=false} 时连 {@code RedissonClient} 也不建</b>：客户端是锁这条链的唯一用户
 * （幂等走 {@code StringRedisTemplate}，Task 26）。留着它意味着"关掉锁却仍然要求 Redis 可达"，
 * 那会把一个退路开关变成启动依赖。
 */
@AutoConfiguration
@ConditionalOnClass(RedissonClient.class)
@ConditionalOnProperty(prefix = "myddd.consistency", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MydddConsistencyProperties.class)
public class MydddConsistencyAutoConfiguration {

    /** 分布式锁分支：{@code myddd.consistency.lock.enabled=false} 时整块退让。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(DataRedisConnectionDetails.class)
    @ConditionalOnProperty(prefix = "myddd.consistency.lock", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    static class RedissonLockConfiguration {

        /**
         * 建 Redisson 客户端。<b>一个条件、两个类型</b>（{@code @ConditionalOnMissingBean} 不是可重复注解，
         * 一手实测编译期就拒）：容器里已经有 {@link RedissonClient}、或已经有 {@link LockPort}，都不建。
         * <ul>
         *   <li>自带 {@code RedissonClient} 时不重复建；</li>
         *   <li>自带 {@code LockPort} 时也不必建——这个客户端在锁这条链上的<b>唯一用户</b>就是
         *       {@link DefaultLockPort}，为一个人不用的对象要求 Redis 可达，等于把一个纯优化变成启动依赖。</li>
         * </ul>
         *
         * <p><b>{@code destroyMethod} 显式写死而不是靠容器的 "(inferred)"</b>：
         * 推断规则只找 {@code close}/{@code shutdown}，命中与否取决于 Redisson 未来是否改名，
         * 而漏掉的后果是应用关停时留着 netty 线程组与连接池——一个"进程退不出去"的故障。
         * 这里不另加断言：关停效果只有真连接可观察（{@code LockRedisIT} 的关停用例），
         * 对桩对象断言"shutdown 被调过"能证明的是 Spring 的行为，不是这里的。
         */
        @Bean(destroyMethod = "shutdown")
        @ConditionalOnMissingBean({RedissonClient.class, LockPort.class})
        RedissonClient mydddRedissonClient(MydddConsistencyProperties properties,
                ObjectProvider<DataRedisConnectionDetails> connectionDetails) {
            DataRedisConnectionDetails details = connectionDetails.getIfAvailable();
            if (details == null) {
                throw new FrameworkException("myddd.consistency.lock.enabled=true（缺省值）需要一个 "
                        + "DataRedisConnectionDetails bean，容器里没有它。要么引入 Boot 的 redis 自动装配"
                        + "（spring-boot-data-redis）并配 spring.data.redis.host 等键——测试里用 "
                        + "myddd-test-starter 的 MydddRedisContainer，要么关掉锁能力："
                        + "myddd.consistency.lock.enabled=false（关掉后幂等能力仍可用，它不经过 Redisson）");
            }
            return RedissonClientFactory.create(details, properties);
        }

        @Bean
        @ConditionalOnMissingBean(LockPort.class)
        LockPort mydddLockPort(RedissonClient client, MydddConsistencyProperties properties) {
            return new DefaultLockPort(client, properties);
        }
    }
}
