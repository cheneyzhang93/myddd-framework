package org.deep361.myddd.cache.l2;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.deep361.myddd.cache.autoconfigure.MydddCacheProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RedisLevelFactory} 把 {@code myddd.cache.key-prefix} 与 {@code myddd.cache.redis.*} 落成
 * L2 配置的证据（Plan 3 Task 23）。
 *
 * <p>用 {@link StubRedisConnectionFactory} 而不是真容器：本类问的是"配置落成了什么"，
 * 一次网络往返都改变不了答案；真 Redis 里的键形状、JSON 载荷与 TTL 由 {@code CacheRedisIT} 取证（§0.3-7）。
 *
 * <p>序列化一律以<b>写出来的字节</b>为证据，不做序列化器的类型断言：实测（4.1.1）
 * {@code SerializationPair} 只暴露 {@code getReader()/getWriter()}，而 {@code getWriter()} 返回的是
 * {@code DefaultRedisElementWriter} 包装器，把它往 {@code RedisSerializer} 上转会直接 ClassCastException。
 * 字节本来也是更直接的证据——Redis 客户端读到的就是这串东西。
 */
class RedisLevelFactoryTest {

    private static final String NAME = "dict";

    @Test
    void should_default_the_second_level_to_a_ttl_a_prefix_and_readable_values() {
        RedisCacheConfiguration config = configurationOf(new MydddCacheProperties(), NAME);

        // 缺省必须带 TTL：没有过期时间的 Redis 缓存＝删掉的数据在重启应用之后还能读回来。
        assertThat(appliedTtl(config)).isEqualTo(Duration.ofMinutes(30));
        // 前缀段 + 分隔符 + 缓存名 + 分隔符：SEPARATOR 在 spring-data-redis 4.1.1 里就是 "::"（javap 实测）。
        assertThat(config.getKeyPrefixFor(NAME)).isEqualTo("myddd:cache::dict::");
        // 键写成 UTF-8 原文：redis-cli 打得进、查得出，这是前缀段能被 --scan 圈定的前提。
        assertThat(stringOf(config.getKeySerializationPair(), "probe")).isEqualTo("probe");
    }

    @Test
    void should_apply_a_configured_ttl_and_prefix() {
        MydddCacheProperties properties = new MydddCacheProperties();
        properties.setKeyPrefix("shop:cache");
        properties.getRedis().setTtl(Duration.ofSeconds(90));

        RedisCacheConfiguration config = configurationOf(properties, NAME);

        assertThat(appliedTtl(config)).isEqualTo(Duration.ofSeconds(90));
        assertThat(config.getKeyPrefixFor(NAME)).isEqualTo("shop:cache::dict::");
    }

    @Test
    void should_match_the_first_level_on_the_null_policy() {
        // cache-null-values 默认关闭时必须在 L2 也关掉：只在 L1 关的话，"查不到的 id"仍会在 Redis 里留下占位符，
        // 于是新增的数据在 TTL 内跨节点都看不见——那正是这条开关想避免的事。
        assertThat(configurationOf(new MydddCacheProperties(), NAME).getAllowCacheNullValues()).isFalse();

        MydddCacheProperties caching = new MydddCacheProperties();
        caching.setCacheNullValues(true);
        assertThat(configurationOf(caching, NAME).getAllowCacheNullValues()).isTrue();
    }

    @Test
    void should_override_only_the_ttl_of_the_named_cache() {
        MydddCacheProperties properties = new MydddCacheProperties();
        properties.getRedis().setTtl(Duration.ofMinutes(30));
        properties.getRedis().getTtlPerCache().put("productDetail", Duration.ofMinutes(5));

        RedisCacheManager manager = RedisLevelFactory.create(new StubRedisConnectionFactory(), properties);

        assertThat(manager.getCacheConfigurations()).containsOnlyKeys("productDetail");
        RedisCacheConfiguration override = manager.getCacheConfigurations().get("productDetail");
        assertThat(appliedTtl(override)).isEqualTo(Duration.ofMinutes(5));
        // 覆盖只改 TTL：前缀与序列化沿用缺省那条，否则一条 ttl 覆盖会把键形状一起改掉。
        assertThat(override.getKeyPrefixFor("productDetail")).isEqualTo("myddd:cache::productDetail::");
        assertThat(stringOf(override.getValueSerializationPair(), new SampleValue(1, "choco")))
                .as("TTL 覆盖不该把序列化方式一起换掉")
                .contains("\"name\":\"choco\"");
        // 名单外的名字仍走缺省 TTL——覆盖是"点名生效"，不是"全局改写"。
        assertThat(appliedTtl(configurationOf(properties, NAME))).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void should_serialize_a_non_serializable_value_as_readable_json() {
        MydddCacheProperties properties = new MydddCacheProperties();
        properties.setCacheNullValues(true);
        RedisCacheConfiguration config = configurationOf(properties, NAME);

        // 这条用例就是"为什么不用 JDK 序列化"：SampleValue 不实现 Serializable，
        // 缺省的 JdkSerializationRedisSerializer 会在第一次 put 抛 SerializationException，
        // 而 JSON 不仅收得下，落进 Redis 的还是人能读、跨语言能用的原文。
        byte[] bytes = bytesOf(config.getValueSerializationPair(), new SampleValue(7, "choco"));

        assertThat(new String(bytes, StandardCharsets.UTF_8))
                .contains("\"id\":7")
                .contains("\"name\":\"choco\"");
        Object restored = config.getValueSerializationPair().read(ByteBuffer.wrap(bytes));
        assertThat(restored).isInstanceOf(SampleValue.class)
                .isEqualTo(new SampleValue(7, "choco"));
    }

    @Test
    void should_write_the_key_bytes_the_prefix_promises() {
        RedisCacheConfiguration config = configurationOf(new MydddCacheProperties(), NAME);

        String key = config.getKeyPrefixFor(NAME) + "1";
        byte[] bytes = bytesOf(config.getKeySerializationPair(), key);

        // 键序列化成 UTF-8 原文：这一条让 "redis-cli --scan --pattern 'myddd:cache::dict::*'" 圈得出范围。
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("myddd:cache::dict::1");
    }

    @Test
    void should_restrict_the_second_level_to_a_configured_whitelist() {
        MydddCacheProperties properties = new MydddCacheProperties();
        properties.getCacheNames().add(NAME);
        properties.getCacheNames().add("config");

        RedisCacheManager manager = RedisLevelFactory.create(new StubRedisConnectionFactory(), properties);

        assertThat(manager.getCacheNames()).containsExactly(NAME, "config");
        // 名单外拿不到缓存，与 L1 同一条语义：两层不一致时组合管理器无从判断该返回 null 还是"只有 L2"。
        assertThat(manager.getCache("not-listed")).isNull();
    }

    @Test
    void should_create_second_level_caches_on_demand_when_no_names_are_configured() {
        RedisCacheManager manager =
                RedisLevelFactory.create(new StubRedisConnectionFactory(), new MydddCacheProperties());

        assertThat(manager.getCache("whichever")).isNotNull();
    }

    private static RedisCacheConfiguration configurationOf(MydddCacheProperties properties, String name) {
        RedisCacheManager manager = RedisLevelFactory.create(new StubRedisConnectionFactory(), properties);
        RedisCache cache = (RedisCache) manager.getCache(name);
        assertThat(cache)
                .as("名单外才会拿不到缓存，这里必须建得出 RedisCache 才能读到它的生效配置")
                .isNotNull();
        return cache.getCacheConfiguration();
    }

    /** 值/键写成落到 Redis 里的那串字节；{@code write} 返回的缓冲区按剩余可读长度取值。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static byte[] bytesOf(SerializationPair pair, Object value) {
        ByteBuffer buffer = ((ByteBuffer) pair.write(value)).duplicate();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static String stringOf(SerializationPair<?> pair, Object value) {
        return new String(bytesOf(pair, value), StandardCharsets.UTF_8);
    }

    private static Duration appliedTtl(RedisCacheConfiguration config) {
        return config.getTtlFunction().getTimeToLive("any", "value");
    }

    /** 刻意<b>不</b>实现 {@code Serializable} 的被缓存值：与业务侧的值对象/DTO 同一形态。 */
    record SampleValue(int id, String name) {
    }
}
