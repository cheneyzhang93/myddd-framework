package org.deep361.myddd.cache.l2;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.deep361.myddd.cache.autoconfigure.MydddCacheProperties;
import org.springframework.data.redis.cache.CacheKeyPrefix;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * L2（Redis）那一层的构造：把 {@code myddd.cache.key-prefix} 与 {@code myddd.cache.redis.*}
 * 落成一台 {@link RedisCacheManager}。
 *
 * <p><b>值序列化用 JSON（Jackson 3），不用 JDK 序列化</b>：{@code RedisCacheConfiguration} 的缺省是
 * {@code JdkSerializationRedisSerializer}，它要求被缓存的对象实现 {@code Serializable}——本脚手架的值对象
 * 与 DTO 都不实现它（序列化约定由 Jackson 承担，见 {@code myddd.cache} 之上的模块边界），缺省值因此会在
 * 第一次 {@code put} 时抛 {@code SerializationException}。换成 JSON 还有两个附带收益：
 * Redis 里的值人能读、跨语言客户端能用。
 *
 * <p><b>建这一层不建立连接</b>：{@code RedisCacheWriter.nonLockingRedisCacheWriter} 只握住工厂引用，
 * 键空间操作到第一次读写才发生（{@code CacheRedisIT} 之外的那些装配用例因此可以在没有 Redis 的机器上跑）。
 */
public final class RedisLevelFactory {

    private RedisLevelFactory() {
    }

    public static RedisCacheManager create(RedisConnectionFactory connectionFactory,
            MydddCacheProperties properties) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(properties.getRedis().getTtl())
                // 实测（4.1.1）：prefixed(p) 产出 p + 缓存名 + "::"，只补尾巴不补中间的分隔符，
                // 所以这里把 SEPARATOR 接到用户写的那段命名空间后面，键形状才是 myddd:cache::dict::1。
                .computePrefixWith(CacheKeyPrefix.prefixed(
                        properties.getKeyPrefix() + CacheKeyPrefix.SEPARATOR))
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(RedisSerializer.string()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(RedisSerializer.json()));
        if (!properties.isCacheNullValues()) {
            defaults = defaults.disableCachingNullValues();
        }

        RedisCacheManager.RedisCacheManagerBuilder builder = RedisCacheManager
                .builder(RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory))
                .cacheDefaults(defaults);

        // 名单外不许动态建：与 L1 的白名单语义配平，否则组合管理器对同一个名字会一层有一层无。
        List<String> names = properties.getCacheNames();
        if (!names.isEmpty()) {
            builder.initialCacheNames(new LinkedHashSet<>(names)).disableCreateOnMissingCache();
        }
        for (Map.Entry<String, Duration> override : properties.getRedis().getTtlPerCache().entrySet()) {
            builder.withCacheConfiguration(override.getKey(), defaults.entryTtl(override.getValue()));
        }
        RedisCacheManager manager = builder.build();
        // 非 bean 没有初始化回调；名单里的名字靠它建出来（见 CaffeineLevelFactory 里同一条说明）
        manager.afterPropertiesSet();
        return manager;
    }
}
