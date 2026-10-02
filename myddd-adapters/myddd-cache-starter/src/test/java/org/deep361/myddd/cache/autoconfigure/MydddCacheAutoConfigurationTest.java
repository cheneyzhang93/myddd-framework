package org.deep361.myddd.cache.autoconfigure;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Policy;
import java.time.Duration;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.NullValue;
import org.springframework.cache.support.SimpleCacheManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * {@code type=caffeine} 分支的装配面：bean 在不在、以什么身份在、配置键有没有真的走进 Caffeine。
 *
 * <p>缺省形态是 {@code multi}（两级），它需要 {@code RedisConnectionFactory}，因此本文件的 runner 统一显式
 * 写了 {@code myddd.cache.type=caffeine}；两级的装配面在 {@code MydddCacheTwoLevelAutoConfigurationTest}，
 * 真 Redis 的证据在 {@code CacheRedisIT}。
 *
 * <p>用 {@code ApplicationContextRunner} 而不是真上下文（与持久化侧同一分工）：这里只问条件装配，
 * 真上下文的证据归 IT。
 *
 * <p><b>配置键的证据一律取"原生缓存上读得出的策略值"</b>，而不是"调用了一次 setter"：
 * {@code manager.setCaffeine(…)} 与 {@code CaffeineCacheManager#createCaffeineCache} 之间隔着 Spring 的
 * 一段构造，只有从 {@code getNativeCache().policy()} 读回来的数字才能证明"配的值最终生效到了每一个缓存"。
 */
class MydddCacheAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddCacheAutoConfiguration.class))
            .withPropertyValues("myddd.cache.type=caffeine");

    @Test
    void should_back_off_entirely_when_the_switch_is_off() {
        runner.withPropertyValues("myddd.cache.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(CacheManager.class);
            // 退让要退得干净：不产出 bean，也不留下一个起不来的半成品上下文。
            assertThat(context).hasNotFailed();
        });
    }

    @Test
    void should_assemble_a_plain_caffeine_manager_for_the_single_level_form() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CacheManager.class);
            assertThat(context.getBean(CacheManager.class)).isInstanceOf(CaffeineCacheManager.class);
        });
    }

    @Test
    void should_step_aside_for_a_cache_manager_the_application_provides() {
        runner.withBean(CacheManager.class, SimpleCacheManager::new).run(context -> {
            assertThat(context).hasSingleBean(CacheManager.class);
            assertThat(context.getBean(CacheManager.class)).isNotInstanceOf(CaffeineCacheManager.class);
        });
    }

    @Test
    void should_still_validate_when_the_framework_steps_aside() {
        // 让位不等于失声：配置 bean 的 @PostConstruct 与这里是否产出 bean 无关。
        // 少了这条，"把校验挪进 @Bean 方法"这个改法会全绿通过，而它的表现是坏配置静默生效。
        runner.withBean(CacheManager.class, SimpleCacheManager::new)
                .withPropertyValues("myddd.cache.caffeine.max-size=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .hasRootCauseInstanceOf(FrameworkException.class)
                            .hasStackTraceContaining("myddd.cache.caffeine.max-size");
                });
    }

    @Test
    void should_put_the_default_size_and_expiration_on_every_cache() {
        runner.run(context -> {
            Cache<Object, Object> nativeCache = nativeCacheOf(context.getBean(CacheManager.class), "dict");
            assertThat(evictionMaximum(nativeCache)).isEqualTo(1000L);
            assertThat(expirationOf(nativeCache)).isEqualTo(Duration.ofMinutes(10));
        });
    }

    @Test
    void should_apply_a_configured_max_size_to_the_native_cache() {
        runner.withPropertyValues("myddd.cache.caffeine.max-size=7").run(context ->
                assertThat(evictionMaximum(nativeCacheOf(context.getBean(CacheManager.class), "dict")))
                        .isEqualTo(7L));
    }

    @Test
    void should_apply_a_configured_expiration_to_the_native_cache() {
        runner.withPropertyValues("myddd.cache.caffeine.expire-after-write=45s").run(context ->
                assertThat(expirationOf(nativeCacheOf(context.getBean(CacheManager.class), "dict")))
                        .isEqualTo(Duration.ofSeconds(45)));
    }

    @Test
    void should_create_caches_on_demand_when_no_names_are_configured() {
        runner.run(context -> {
            CacheManager manager = context.getBean(CacheManager.class);
            // 白名单为空＝沿用 Spring Cache 的动态创建：新名字出现就建一个，不报错。
            assertThat(manager.getCache("whichever")).isNotNull();
            assertThat(manager.getCacheNames()).contains("whichever");
        });
    }

    @Test
    void should_restrict_the_known_names_to_a_configured_whitelist() {
        runner.withPropertyValues("myddd.cache.cache-names[0]=dict", "myddd.cache.cache-names[1]=config")
                .run(context -> {
                    CacheManager manager = context.getBean(CacheManager.class);
                    assertThat(manager.getCacheNames()).containsExactly("dict", "config");
                    assertThat(manager.getCache("dict")).isNotNull();
                    // 名单外拿不到缓存，@Cacheable 随之静默不缓存——这是 cache-names 的代价，写在 javadoc 里。
                    assertThat(manager.getCache("not-listed")).isNull();
                });
    }

    @Test
    void should_refuse_null_values_until_the_policy_is_switched_on() {
        runner.run(context -> {
            org.springframework.cache.Cache cache = context.getBean(CacheManager.class).getCache("dict");
            assertThat(cache).isNotNull();
            // 缺省拒绝存 null，而不是静默收下：静默收下的表现是"这条数据建好了，TTL 内查不到"。
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> cache.put("any", null))
                    .withMessageContaining("null");
        });
    }

    @Test
    void should_store_a_placeholder_for_null_when_the_policy_is_switched_on() {
        runner.withPropertyValues("myddd.cache.cache-null-values=true").run(context -> {
            CacheManager manager = context.getBean(CacheManager.class);
            assertThat(manager).isInstanceOf(CaffeineCacheManager.class);
            assertThat(((CaffeineCacheManager) manager).isAllowNullValues()).isTrue();

            org.springframework.cache.Cache cache = manager.getCache("dict");
            cache.put("missing", null);
            // 三条断言一起才叫证据。Spring 7 把 Cache.get(Object) 的返回改成了 ValueWrapper，
            // 于是"命中一个缓存下来的 null"与"没命中"第一次成为可直接区分的两件事：
            // 原生缓存里是占位符（这才挡住穿透）、get 回了 wrapper（命中）、wrapper 里的值是 null（业务语义不变）。
            assertThat(nativeCacheOf(manager, "dict").getIfPresent("missing")).isInstanceOf(NullValue.class);
            org.springframework.cache.Cache.ValueWrapper hit = cache.get("missing");
            assertThat(hit).as("缓存了 null 之后不该再算未命中").isNotNull();
            assertThat(hit.get()).as("业务侧读到的仍然是 null").isNull();
        });
    }

    @Test
    void should_be_assembled_before_boots_own_cache_auto_configuration() {
        // 这条用例的三个设计点各被一个变异验过（M9/M10），少一个就钉不住：
        // ① 必须 @EnableCaching。一手实测（javap 读 spring-boot-cache-4.1.1.jar 的类注解）：Boot 的
        //    CacheAutoConfiguration 带 @ConditionalOnBean(CacheAspectSupport.class)，也就是说
        //    <b>业务没开缓存代理时它整块退场</b>。不加 @EnableCaching 的话，这里比的是"我们 vs 没人"，
        //    删掉 beforeName 照样全绿（首轮实测就是这样，M9/M10 双双存活）。
        // ② Boot 的类写在**前面**。`AutoConfigurations` 会按 @AutoConfiguration 的 before/after（含 beforeName）
        //    做一次拓扑排序，只有把我们的类写在后面，"谁先装配"才由那条排序声明决定，而不是由实参顺序决定。
        // ③ 断言"配置值到了原生缓存"而不是 instanceof CaffeineCacheManager。classpath 上有 Caffeine 时
        //    Boot 自己造的也是 CaffeineCacheManager，类型断言分不出是谁造的；`max-size=7` 只有我们读得懂。
        new ApplicationContextRunner()
                .withUserConfiguration(CachingEnabled.class)
                .withConfiguration(AutoConfigurations.of(
                        CacheAutoConfiguration.class, MydddCacheAutoConfiguration.class))
                .withPropertyValues("myddd.cache.type=caffeine", "myddd.cache.caffeine.max-size=7")
                .run(context -> {
                    assertThat(context).hasSingleBean(CacheManager.class);
                    assertThat(evictionMaximum(nativeCacheOf(context.getBean(CacheManager.class), "dict")))
                            .isEqualTo(7L);
                });
    }

    /** 缓存代理由业务开启（框架不接管 {@code @EnableCaching}，见装配类 javadoc），这里只是把对手请进场。 */
    @org.springframework.cache.annotation.EnableCaching
    static class CachingEnabled {
    }

    @Test
    void should_measure_the_control_group_of_boots_own_caffeine_manager() {
        // 上一条用例要有对手在场才成立，这条就是"对手确实在场"的对照证据：
        // @EnableCaching 之后 Boot 的 CacheAutoConfiguration 会自己造一个 CaffeineCacheManager。
        // 它读的是 spring.cache.*，完全不认识 myddd.cache.caffeine.max-size——所以上一条用 7 这个数字当判据。
        // 没有这条对照，"我们的装配类赢了"与"根本没有对手"在测试里长得一模一样。
        new ApplicationContextRunner()
                .withUserConfiguration(CachingEnabled.class)
                .withConfiguration(AutoConfigurations.of(CacheAutoConfiguration.class))
                .withPropertyValues("myddd.cache.caffeine.max-size=7")
                .run(context -> {
                    assertThat(context).hasSingleBean(CacheManager.class);
                    assertThat(context.getBean(CacheManager.class)).isInstanceOf(CaffeineCacheManager.class);
                    Cache<Object, Object> nativeCache = nativeCacheOf(context.getBean(CacheManager.class), "dict");
                    assertThat(nativeCache.policy().eviction())
                            .as("Boot 自己的管理器不该读到 myddd.cache.*")
                            .isEmpty();
                });
    }

    private static Cache<Object, Object> nativeCacheOf(CacheManager manager, String name) {
        org.springframework.cache.Cache cache = manager.getCache(name);
        assertThat(cache).isInstanceOf(CaffeineCache.class);
        return ((CaffeineCache) cache).getNativeCache();
    }

    private static long evictionMaximum(Cache<Object, Object> nativeCache) {
        Policy.Eviction<Object, Object> eviction = nativeCache.policy().eviction()
                .orElseThrow(() -> new AssertionError("原生缓存没有生效中的逐出策略"));
        return eviction.getMaximum();
    }

    private static Duration expirationOf(Cache<Object, Object> nativeCache) {
        Policy.FixedExpiration<Object, Object> expiration = nativeCache.policy().expireAfterWrite()
                .orElseThrow(() -> new AssertionError("原生缓存没有生效中的写后过期策略"));
        return expiration.getExpiresAfter();
    }
}
