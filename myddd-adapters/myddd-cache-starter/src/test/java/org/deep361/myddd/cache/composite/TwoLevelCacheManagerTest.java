package org.deep361.myddd.cache.composite;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TwoLevelCacheManager} 的组合面（Plan 3 Task 23）：同一个名字给同一个包装实例、名字取两层并集、
 * 任一层给不出缓存时返回 {@code null}、以及"读不到 null 策略就不组合"的防御。
 *
 * <p>两层的真身在 {@code TwoLevelCacheTest} 与 {@code CacheRedisIT}，这里只管"名字与实例"这一层。
 */
class TwoLevelCacheManagerTest {

    @Test
    void should_refuse_a_missing_level_manager() {
        SimpleCacheManager level = managerOf(List.of("dict"));

        assertThatThrownBy(() -> new TwoLevelCacheManager(null, level, false))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("两个非空的层级管理器")
                .hasMessageContaining("L1=null");
        assertThatThrownBy(() -> new TwoLevelCacheManager(level, null, false))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("L2=null");
    }

    @Test
    void should_return_the_same_two_level_cache_for_repeated_lookups_of_one_name() {
        TwoLevelCacheManager manager = new TwoLevelCacheManager(
                managerOf(List.of("dict", "config")), managerOf(List.of("dict", "config")), true);

        // @Cacheable(sync=true) 与自建的两级视图必须落在同一个 L1/L2 对上，否则两个包装对象各维护一份回填状态。
        assertThat(manager.getCache("dict")).isSameAs(manager.getCache("dict"));
        // 反过来也必须成立：每个名字各一个对象，否则 config 的读写会落到 dict 那一层去。
        assertThat(manager.getCache("dict")).isNotSameAs(manager.getCache("config"));
    }

    @Test
    void should_compose_only_the_names_both_levels_hold() {
        TwoLevelCacheManager manager = new TwoLevelCacheManager(
                managerOf(List.of("a", "b")), managerOf(List.of("b", "c")), true);

        TwoLevelCache composed = (TwoLevelCache) manager.getCache("b");
        assertThat(composed).isNotNull();
        assertThat(composed.getName()).isEqualTo("b");

        // 两种缺法都要返回 null（Spring 的契约：null 表示"没有这个缓存"，@Cacheable 随之退化为不缓存）。
        // 我们不去造"只有 L2 的两级缓存"——那会让同一个缓存名在不同实例上表现不同，
        // 而配置正确时这条路不可达：两层的白名单由装配处用同一份 myddd.cache.cache-names 配平。
        assertThat(manager.getCache("a")).as("只有 L1 认得这个名字").isNull();
        assertThat(manager.getCache("c")).as("只有 L2 认得这个名字").isNull();
        assertThat(manager.getCache("zzz")).as("两层都不认得").isNull();
    }

    @Test
    void should_report_the_union_of_both_levels_in_level_one_first_order() {
        TwoLevelCacheManager manager = new TwoLevelCacheManager(
                managerOf(List.of("b", "a")), managerOf(List.of("a", "c")), true);

        // 并集而不是只取 L1：任一层被外部预热过的名字都该被看见，漏报会让运维以为某个缓存名没生效。
        assertThat(manager.getCacheNames()).containsExactly("b", "a", "c");
    }

    @Test
    void should_expose_the_underlying_level_managers_for_diagnostics() {
        SimpleCacheManager level1 = managerOf(List.of("dict"));
        SimpleCacheManager level2 = managerOf(List.of("dict"));
        TwoLevelCacheManager manager = new TwoLevelCacheManager(level1, level2, true);

        assertThat(manager.getLevel1()).isSameAs(level1);
        assertThat(manager.getLevel2()).isSameAs(level2);
    }

    @Test
    void should_refuse_to_compose_a_level_that_hides_its_null_policy() {
        // Spring 7 的 Cache 接口没有 isAllowNullValues()：读不到策略就没法保证 cache-null-values 在两层一致，
        // 所以拒绝组合而不是赌它一致。两层各钉一条并且顺序可辨：只查一层的话，另一层的漏检会全绿通过。
        TwoLevelCacheManager badLevel1 = new TwoLevelCacheManager(
                managerOf(new UnadaptingCache("dict")), managerOf(List.of("dict")), true);
        assertThatThrownBy(() -> badLevel1.getCache("dict"))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("的 L1 实现是")
                .hasMessageContaining(UnadaptingCache.class.getName())
                .hasMessageContaining("AbstractValueAdaptingCache");

        TwoLevelCacheManager badLevel2 = new TwoLevelCacheManager(
                managerOf(List.of("dict")), managerOf(new UnadaptingCache("dict")), true);
        assertThatThrownBy(() -> badLevel2.getCache("dict"))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("的 L2 实现是")
                .hasMessageContaining(UnadaptingCache.class.getName());
    }

    @Test
    void should_not_remember_a_rejected_name() {
        TwoLevelCacheManager manager = new TwoLevelCacheManager(
                managerOf(new UnadaptingCache("dict")), managerOf(List.of("dict")), true);

        assertThatThrownBy(() -> manager.getCache("dict")).isInstanceOf(FrameworkException.class);
        // 修好配置之前每一次读都该报同一个错，而不是第一次抛、之后静默返回 null。
        assertThatThrownBy(() -> manager.getCache("dict")).isInstanceOf(FrameworkException.class);
    }

    /** 只装名单内名字的层级管理器：名单外 {@code getCache} 返回 null，正是组合层要判的那两件事。 */
    private static SimpleCacheManager managerOf(List<String> names) {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(names.stream().map(ConcurrentMapCache::new).toList());
        // 手工 new 出来的管理器没有容器替它调初始化回调：不点这一步，一个缓存都登记不上。
        manager.afterPropertiesSet();
        return manager;
    }

    /** 只持有这一个缓存的管理器：用来把某一层换成非 {@code AbstractValueAdaptingCache} 的实现。 */
    private static CacheManager managerOf(Cache cache) {
        return new CacheManager() {

            @Override
            public Cache getCache(String name) {
                return cache.getName().equals(name) ? cache : null;
            }

            @Override
            public Collection<String> getCacheNames() {
                return List.of(cache.getName());
            }
        };
    }

    /** 不继承 {@code AbstractValueAdaptingCache} 的最小 {@code Cache}：null 策略在它身上读不到。 */
    private static final class UnadaptingCache implements Cache {

        private final String name;

        UnadaptingCache(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Object getNativeCache() {
            return name;
        }

        @Override
        public ValueWrapper get(Object key) {
            return null;
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            return null;
        }

        @Override
        public <T> T get(Object key, Callable<T> valueLoader) {
            throw new IllegalStateException("本夹具不承载数据");
        }

        @Override
        public void put(Object key, Object value) {
        }

        @Override
        public void evict(Object key) {
        }

        @Override
        public void clear() {
        }
    }
}
