package org.deep361.myddd.cache.autoconfigure;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Policy;
import java.time.Duration;
import java.util.Optional;
import org.deep361.myddd.cache.composite.TwoLevelCache;
import org.deep361.myddd.cache.composite.TwoLevelCacheManager;
import org.deep361.myddd.cache.l2.StubRedisConnectionFactory;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缺省形态（{@code type=multi}）的装配面（Plan 3 Task 23）。
 *
 * <p>用 {@link StubRedisConnectionFactory}：这里问的是"两级各自在不在、容器里有几个 {@code CacheManager}、
 * 配置键有没有落进<b>两层</b>"，这些都不需要网络往返（§0.3-7）。真 Redis 的证据在 {@code CacheRedisIT}。
 */
class MydddCacheTwoLevelAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddCacheAutoConfiguration.class))
            .withBean(RedisConnectionFactory.class, StubRedisConnectionFactory::new);

    @Test
    void should_build_two_levels_when_nothing_is_configured() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CacheManager.class);
            // 缺省就是两级：§1.2.1 写的是终态缺省，"缺省只有 L1"会让单点重启后全丢变成默认交付行为。
            assertThat(context.getBean(CacheManager.class)).isInstanceOf(TwoLevelCacheManager.class);
            assertThat(context).hasNotFailed();
        });
    }

    @Test
    void should_build_two_levels_when_multi_is_named_explicitly() {
        runner.withPropertyValues("myddd.cache.type=multi").run(context ->
                assertThat(context.getBean(CacheManager.class)).isInstanceOf(TwoLevelCacheManager.class));
    }

    @Test
    void should_register_exactly_one_cache_manager_bean() {
        runner.run(context -> {
            // 两个层级管理器在 @Bean 方法内部构造、不注册成 bean：容器里同时有三个 CacheManager 时，
            // @Cacheable 的按类型注入要靠 @Primary 才不歧义，而业务自己的
            // @ConditionalOnMissingBean(CacheManager.class) 会因为我们那两个内部件而误判"已经有管理器了"。
            assertThat(context.getBeanNamesForType(CacheManager.class)).hasSize(1);
            assertThat(context).doesNotHaveBean(CaffeineCacheManager.class);
            assertThat(context).doesNotHaveBean(org.springframework.data.redis.cache.RedisCacheManager.class);
        });
    }

    @Test
    void should_fail_startup_when_the_two_level_form_has_no_connection() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MydddCacheAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .hasRootCauseInstanceOf(FrameworkException.class)
                            // 失败信息必须给两条出路：只说"没有 RedisConnectionFactory"等于让人去翻文档。
                            .hasStackTraceContaining("spring.data.redis.host")
                            .hasStackTraceContaining("myddd.cache.type=caffeine");
                });
    }

    @Test
    void should_still_step_aside_for_an_application_provided_manager() {
        // 让位纪律在两个分支上都要成立：只在 caffeine 分支写 @ConditionalOnMissingBean 的话，
        // 自带管理器的应用在缺省形态下会拿到第二个 CacheManager 而歧义失败。
        runner.withBean(CacheManager.class, SimpleCacheManager::new).run(context -> {
            assertThat(context).hasSingleBean(CacheManager.class);
            assertThat(context.getBean(CacheManager.class)).isNotInstanceOf(TwoLevelCacheManager.class);
        });
    }

    @Test
    void should_not_assemble_the_single_level_bean_when_the_form_is_multi() {
        // 两个分支必须互斥：havingValue 写错（比如都缺省匹配）会让容器里出现两个 CacheManager，
        // 而 hasSingleBean 的断言在两分支都产出同名 bean 时反而更晚才炸，所以这里按类型点名。
        runner.run(context -> assertThat(context).doesNotHaveBean(CaffeineCacheManager.class));
    }

    @Test
    void should_put_the_configured_size_on_the_first_level_of_the_two_level_manager() {
        runner.withPropertyValues("myddd.cache.caffeine.max-size=7").run(context -> {
            TwoLevelCacheManager manager = (TwoLevelCacheManager) context.getBean(CacheManager.class);
            CaffeineCache firstLevel = (CaffeineCache) manager.getLevel1().getCache("dict");

            // 只断言"getLevel1() 是 CaffeineCacheManager"不够：键值要落到原生缓存上才是配到位了。
            assertThat(firstLevel).isNotNull();
            assertThat(evictionMaximum(firstLevel.getNativeCache())).isEqualTo(7L);
        });
    }

    @Test
    void should_apply_the_same_whitelist_to_both_levels() {
        runner.withPropertyValues("myddd.cache.cache-names[0]=dict", "myddd.cache.cache-names[1]=config")
                .run(context -> {
                    TwoLevelCacheManager manager = (TwoLevelCacheManager) context.getBean(CacheManager.class);
                    // 两层名单不一致时，名单外的名字会一层能建、一层建不出，组合管理器就无从判断该返回什么。
                    assertThat(manager.getCacheNames()).containsExactly("dict", "config");
                    assertThat(manager.getLevel1().getCacheNames()).containsExactly("dict", "config");
                    assertThat(manager.getLevel2().getCacheNames()).containsExactly("dict", "config");
                    assertThat(manager.getCache("not-listed")).isNull();
                });
    }

    @Test
    void should_match_both_levels_on_the_null_policy() {
        runner.withPropertyValues("myddd.cache.cache-null-values=true").run(context -> {
            TwoLevelCacheManager manager = (TwoLevelCacheManager) context.getBean(CacheManager.class);
            TwoLevelCache cache = (TwoLevelCache) manager.getCache("dict");

            // 组合层能建出来本身就是证据：TwoLevelCache 的构造期会核对两层的 null 策略，
            // 任何一层没接住 cache-null-values 都会在这里抛 FrameworkException。
            assertThat(cache.isAllowNullValues()).isTrue();
            assertThat(((CaffeineCache) cache.getLevel1()).isAllowNullValues()).isTrue();
        });
        runner.withPropertyValues("myddd.cache.cache-null-values=false").run(context -> {
            TwoLevelCacheManager manager = (TwoLevelCacheManager) context.getBean(CacheManager.class);
            // 反方向必须同样取"真的组合出一个缓存"作判据：把组合层的 allowNullValues 写死成 true（变异 M56）
            // 在上半个用例里与配置值恰好相同、分不出来，只有这里会炸——构造期的策略核对直接抛
            // FrameworkException，于是"缓存 null"从一条配置退化成了每次读缓存都失败。
            TwoLevelCache cache = (TwoLevelCache) manager.getCache("dict");
            assertThat(cache.isAllowNullValues()).isFalse();
            assertThat(((CaffeineCache) cache.getLevel1()).isAllowNullValues()).isFalse();
        });
    }

    @Test
    void should_put_the_configured_ttl_on_the_second_level() {
        runner.withPropertyValues("myddd.cache.redis.ttl=45s", "myddd.cache.key-prefix=shop:cache")
                .run(context -> {
                    TwoLevelCacheManager manager = (TwoLevelCacheManager) context.getBean(CacheManager.class);
                    org.springframework.data.redis.cache.RedisCache secondLevel =
                            (org.springframework.data.redis.cache.RedisCache) manager.getLevel2().getCache("dict");

                    assertThat(secondLevel).isNotNull();
                    assertThat(secondLevel.getCacheConfiguration().getTtlFunction()
                            .getTimeToLive("any", "value")).isEqualTo(Duration.ofSeconds(45));
                    assertThat(secondLevel.getCacheConfiguration().getKeyPrefixFor("dict"))
                            .isEqualTo("shop:cache::dict::");
                });
    }

    private static long evictionMaximum(Cache<Object, Object> nativeCache) {
        Optional<Policy.Eviction<Object, Object>> eviction = nativeCache.policy().eviction();
        return eviction.orElseThrow(() -> new AssertionError("L1 没有生效中的逐出策略")).getMaximum();
    }
}
