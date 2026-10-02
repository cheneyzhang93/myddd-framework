package org.deep361.myddd.cache.it;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.deep361.myddd.cache.autoconfigure.MydddCacheProperties;
import org.deep361.myddd.cache.composite.TwoLevelCache;
import org.deep361.myddd.cache.composite.TwoLevelCacheManager;
import org.deep361.myddd.cache.l1.CaffeineLevelFactory;
import org.deep361.myddd.cache.l2.RedisLevelFactory;
import org.deep361.myddd.testing.container.MydddRedisContainer;
import org.deep361.myddd.testing.integration.MydddIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.cache.Cache;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两级缓存在<b>真 Redis</b> 上的验收（Plan 3 Task 23）。
 *
 * <p><b>本类只做单元层做不了的那半件事</b>：{@code RedisLevelFactoryTest} 能证明"配置落成了
 * {@code RedisCacheConfiguration}"，但它证明不了写进 Redis 的字节长什么样、TTL 是不是真的挂上了、
 * 以及另一个进程能不能读到同一份数据。这些只能问 Redis 自己。
 *
 * <p><b>命令一律走 {@code connection.xxxCommands()} 分组</b>：4.1.1 把 {@code RedisConnection} 上的
 * {@code get/ttl/keys} 这类直接转发方法标成了 deprecated（javac 实测警告），分组访问才是受支持的写法。
 *
 * <p><b>一切"写进去了吗"的证据都走一条 2 秒的有界等待，值本身仍然严格断言</b>：实测（4.1.1 + Lettuce +
 * 共享原生连接）紧邻 {@code RedisCache.put} 之后发 {@code GET}，7 组里有 3 组返回 {@code null}，
 * 而同一条键的 {@code TTL}＝60、{@code KEYS *} 列出它、{@code DBSIZE}＝1，且 250ms 后同一条 {@code GET}
 * 拿到完整 JSON——即"键确实落在服务器上"与"这条 GET 说没有"同时成立，滞后发生在客户端且在一秒内收敛。
 * 这是客户端的可见性滞后，不是缓存层的语义（{@code TwoLevelCache} 自己不写缓冲、不回吐旧值：最坏情况是
 * 多打一次库）。所以本用例把"等到看得见"与"看见的东西对不对"分开：等待只兜住滞后并有 2 秒上界，
 * 超时不放宽成跳过，而是给出"两秒内没看见"这条明确的失败。
 *
 * <p><b>无 Docker 时整类跳过而不是失败</b>：基注解 {@link MydddIntegrationTest} 带
 * {@code @Testcontainers(disabledWithoutDocker = true)}，这是 §0.3-7 的实现路径。
 *
 * <p>配置一律走 {@code myddd.cache.*}：上下文里的 {@code CacheManager} 必须是自动装配产出的那一个，
 * 本类不自己造任何缓存 bean（唯一的例外是"第二个节点"，它用同一份 {@link MydddCacheProperties} 现造，
 * 见 {@link #should_share_a_value_written_by_another_node_and_stay_stale_only_until_l1_expires()} 里的构造）。
 */
@MydddIntegrationTest
@ImportTestcontainers(MydddRedisContainer.class)
@TestPropertySource(properties = {
        "myddd.cache.key-prefix=myddd:it",
        "myddd.cache.redis.ttl=60s",
        // Map 键必须用方括号写：不加括号时 relaxed binding 会把 productDetail 规范化成小写，键就匹配不上了。
        "myddd.cache.redis.ttl-per-cache[productDetail]=600s",
        "myddd.cache.cache-null-values=true",
})
class CacheRedisIT {

    private static final Duration VISIBILITY_BUDGET = Duration.ofSeconds(2);

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private MydddCacheProperties properties;

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Autowired
    private CountingReader reader;

    @BeforeEach
    void emptyTheRedisDatabase() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        // L1 是进程内的，跨用例不会自己消失：每个用例用自己的缓存名，这里再清一次兜底。
        for (String name : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(name);
            if (cache instanceof TwoLevelCache twoLevel) {
                twoLevel.getLevel1().clear();
            }
        }
    }

    @Test
    void should_write_a_prefixed_key_with_readable_json_and_a_ttl() {
        twoLevel("dict").put("1", new ProbeValue(7, "choco"));

        String key = "myddd:it::dict::1";
        byte[] stored = awaitRawBytes(key);
        assertThat(stored).as("值必须真的落进 Redis，且是 JSON 原文：JDK 序列化的字节人读不出、别的语言也用不了")
                .isNotNull();
        assertThat(new String(stored, StandardCharsets.UTF_8))
                .contains("\"id\":7")
                .contains("\"name\":\"choco\"");
        // TTL 真的挂在键上（60s 的预算里，还剩 1~60 秒），不是只写在配置对象里。
        assertThat(ttlSeconds(key)).isBetween(1L, 60L);
    }

    @Test
    void should_apply_the_per_cache_ttl_override_to_the_real_key() {
        twoLevel("productDetail").put("sku-1", "v");
        twoLevel("dict").put("1", "v");

        assertThat(ttlSeconds("myddd:it::productDetail::sku-1"))
                .as("myddd.cache.redis.ttl-per-cache 的覆盖要真的落到那条键上")
                .isBetween(61L, 600L);
        assertThat(ttlSeconds("myddd:it::dict::1"))
                .as("覆盖只点名生效，不改写缺省")
                .isBetween(1L, 60L);
    }

    @Test
    void should_backfill_the_local_level_after_it_was_dropped() {
        TwoLevelCache cache = twoLevel("dict");
        cache.put("1", "v");

        // 每轮先清 L1：不清的话第一轮若撞上可见性滞后就会把旧值/空值回填进 L1，之后再也读不到真值。
        ValueWrapper hit = awaitFromLevel2(cache, "1", wrapper -> wrapper != null);
        assertThat(hit.get()).isEqualTo("v");
        // 第二次读不再需要网络往返——这正是两级组合相对"只用 Redis"唯一的收益来源。
        assertThat(cache.getLevel1().get("1")).isNotNull();
    }

    @Test
    void should_share_a_value_written_by_another_node_and_stay_stale_only_until_l1_expires() {
        TwoLevelCache nodeA = twoLevel("dict");
        // 第二个节点：同一份配置、同一个 Redis，L1 是它自己的那份。
        TwoLevelCache nodeB = (TwoLevelCache) new TwoLevelCacheManager(
                CaffeineLevelFactory.create(properties),
                RedisLevelFactory.create(connectionFactory, properties),
                properties.isCacheNullValues()).getCache("dict");

        nodeA.put("shared", "from-a");
        assertThat(awaitFromLevel2(nodeB, "shared", wrapper -> wrapper != null).get()).as("跨节点要能读到对方写的值")
                .isEqualTo("from-a");

        nodeB.put("shared", "from-b");
        assertThat(nodeA.get("shared").get())
                .as("没有失效广播：对方改了 L2，本节点已回填的 L1 不会被打扰")
                .isEqualTo("from-a");

        // 陈旧的上界就是 L1 的寿命（这里用清空 L1 模拟 expire-after-write 到期）——交付语义，不是缺陷。
        assertThat(awaitFromLevel2(nodeA, "shared",
                wrapper -> "from-b".equals(wrapper == null ? null : wrapper.get())).get())
                        .isEqualTo("from-b");
    }

    @Test
    void should_cache_a_null_as_a_real_key_in_redis() {
        TwoLevelCache cache = twoLevel("dict");
        cache.put("missing", null);

        ValueWrapper hit = awaitFromLevel2(cache, "missing", wrapper -> wrapper != null);
        assertThat(hit).as("缓存下来的 null 是命中，不是未命中").isNotNull();
        assertThat(hit.get()).isNull();
        assertThat(awaitRawBytes("myddd:it::dict::missing"))
                .as("Redis 里要留下真键：否则同一次查不到会跨节点重复打库")
                .isNotNull();

        ValueWrapper fromRedis = awaitFromLevel2(cache, "missing", wrapper -> wrapper != null);
        assertThat(fromRedis.get()).as("null 占位符要能过序列化再认回来").isNull();
    }

    @Test
    void should_serve_a_cacheable_call_through_the_assembled_manager() {
        assertThat(reader.load("1")).isEqualTo("value-1");
        assertThat(reader.load("1")).isEqualTo("value-1");

        // 注解这一侧的证据：代理用的就是自动装配出来的两级管理器，而且写进了真 Redis。
        assertThat(reader.calls()).as("第二次读必须由缓存服务，而不是再查一次库").isEqualTo(1);
        assertThat(new String(awaitRawBytes("myddd:it::dict::1"), StandardCharsets.UTF_8)).contains("value-1");
    }

    @Test
    void should_not_write_any_key_outside_the_configured_prefix() {
        twoLevel("dict").put("1", "v");
        twoLevel("productDetail").put("sku-1", "v");
        // 先把两条键等到看得见，再取键空间快照：否则这条断言测的是客户端可见性而不是前缀策略。
        assertThat(awaitRawBytes("myddd:it::dict::1")).isNotNull();
        assertThat(awaitRawBytes("myddd:it::productDetail::sku-1")).isNotNull();

        Set<String> keys = allKeys();
        assertThat(keys).containsExactlyInAnyOrder("myddd:it::dict::1", "myddd:it::productDetail::sku-1");
        // 前缀段是"哪些键属于本应用"的唯一凭据：多写一条裸键，共享实例上的 SCAN 就圈不干净。
        assertThat(keys).allSatisfy(key -> assertThat(key).startsWith("myddd:it::"));
    }

    private TwoLevelCache twoLevel(String name) {
        Cache cache = cacheManager.getCache(name);
        assertThat(cache).as("装配出来的管理器对每个名字都该给出两级缓存").isInstanceOf(TwoLevelCache.class);
        return (TwoLevelCache) cache;
    }

    /**
     * 有界等待"这条键的字节在 Redis 上看得见"：只兜客户端的可见性滞后（见类注释的实测记录），
     * 不对值做任何放宽——超时就是失败，且失败信息说的是"两秒内没看见"。
     */
    private byte[] awaitRawBytes(String key) {
        long deadline = System.nanoTime() + VISIBILITY_BUDGET.toNanos();
        while (true) {
            try (RedisConnection connection = connectionFactory.getConnection()) {
                byte[] stored = connection.stringCommands().get(key.getBytes(StandardCharsets.UTF_8));
                if (stored != null) {
                    return stored;
                }
            }
            waitUntilVisible(deadline, key);
        }
    }

    /** 同样的有界等待，走的是缓存自己的读路径（{@code TwoLevelCache.get}）：每轮清一次 L1，让 L2 说话。 */
    private ValueWrapper awaitFromLevel2(TwoLevelCache cache, Object key,
            java.util.function.Predicate<ValueWrapper> done) {
        long deadline = System.nanoTime() + VISIBILITY_BUDGET.toNanos();
        while (true) {
            cache.getLevel1().clear();
            ValueWrapper value = cache.get(key);
            if (done.test(value)) {
                return value;
            }
            waitUntilVisible(deadline, String.valueOf(key));
        }
    }

    private void waitUntilVisible(long deadline, String key) {
        if (System.nanoTime() >= deadline) {
            throw new AssertionError("Redis 在 " + VISIBILITY_BUDGET + " 内没有让 " + key
                    + " 可见：客户端可见性已超出可接受范围，必须查装配而不是放宽断言");
        }
        try {
            Thread.sleep(20L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private long ttlSeconds(String key) {
        long deadline = System.nanoTime() + VISIBILITY_BUDGET.toNanos();
        while (true) {
            long ttl;
            try (RedisConnection connection = connectionFactory.getConnection()) {
                Long read = connection.keyCommands().ttl(key.getBytes(StandardCharsets.UTF_8));
                ttl = read == null ? -2L : read;
            }
            // -2 = 这条键还不存在。紧邻 put 之后读到 -2 实测出现过（ttl-per-cache 那一组），
            // 与 GET 一样按"可见性滞后"处理；-1（键在但没有过期时间）不在此等之内，直接交给断言判失败。
            if (ttl != -2L) {
                return ttl;
            }
            waitUntilVisible(deadline, key);
        }
    }

    private Set<String> allKeys() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            Set<String> keys = new TreeSet<>();
            for (byte[] key : connection.keyCommands().keys("*".getBytes(StandardCharsets.UTF_8))) {
                keys.add(new String(key, StandardCharsets.UTF_8));
            }
            return keys;
        }
    }

    /** 被缓存的探针值：刻意<b>不</b>实现 {@code Serializable}，与业务侧的值对象/DTO 同一形态。 */
    record ProbeValue(int id, String name) {
    }

    /** 数得清调用次数的读方法：{@code @Cacheable} 生效与否唯一的可见证据就是它。 */
    static class CountingReader {

        private final AtomicInteger calls = new AtomicInteger();

        @Cacheable(cacheNames = "dict")
        public String load(String id) {
            calls.incrementAndGet();
            return "value-" + id;
        }

        int calls() {
            return calls.get();
        }
    }

    /**
     * 缓存代理由业务开启（框架不接管 {@code @EnableCaching}），这里模拟的就是一个开了缓存的业务工程。
     * 嵌套在测试类里，因此只有本用例需要它。
     *
     * <p><b>为什么这里必须自己写 {@code @EnableAutoConfiguration}</b>：测试类一旦带嵌套配置类，
     * Spring Boot 就用它而<b>不再</b>去找同包的 {@code @SpringBootConfiguration}（实测：上下文里的
     * {@code classes} 只有这一个配置类），于是 {@code AutoConfiguration.imports} 整条链都不生效——
     * 第一版因此在真库上栽在 {@code NoSuchBeanDefinitionException: no CacheManager}。而本类的全部证据
     * 都要来自自动装配，自己 new 缓存 bean 等于造假，所以显式把自动装配打开。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableCaching
    static class CachingConfiguration {

        @Bean
        CountingReader countingReader() {
            return new CountingReader();
        }
    }
}
