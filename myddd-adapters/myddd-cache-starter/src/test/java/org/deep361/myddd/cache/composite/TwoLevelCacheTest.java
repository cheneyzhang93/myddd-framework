package org.deep361.myddd.cache.composite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache.ValueRetrievalException;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.AbstractValueAdaptingCache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TwoLevelCache} 的两级语义（Plan 3 Task 23）。
 *
 * <p>L1 用真的 {@code CaffeineCache}（与 {@code CaffeineLevelFactory} 产出的是同一种东西，null 占位符走的
 * 是 Spring 自己的代码），L2 用本文件里的 {@link RecordingCache}：它把每次调用记成一条有序操作日志，
 * 于是"读 L1 命中就不该碰 L2""回填是 L2 读 + L1 写两步"这类<b>只在两级之间才成立</b>的命题才拿得出证据。
 * 真 Redis 的证据在 {@code CacheRedisIT}。
 */
class TwoLevelCacheTest {

    private static final String NAME = "dict";
    private static final Object KEY = "order.status";

    private final RecordingCache level2 = new RecordingCache(NAME, true);
    private final CaffeineCache level1 = caffeine(true);
    private final TwoLevelCache cache = new TwoLevelCache(NAME, level1, level2, true);

    @Test
    void should_backfill_level_one_when_only_level_two_holds_the_value() {
        level2.put(KEY, "v");
        level2.calls.clear();

        assertThat(cache.get(KEY).get()).isEqualTo("v");

        // 回填是两步：读了 L2、写了 L1。少了第二步，同一个 key 的后续每次读都要走一趟网络。
        assertThat(level2.calls).containsExactly("get");
        assertThat(level1.get(KEY).get()).isEqualTo("v");
    }

    @Test
    void should_read_level_one_first_and_never_touch_level_two() {
        cache.getLevel1().put(KEY, "in-l1");
        cache.getLevel2().put(KEY, "in-l2");
        level2.calls.clear();

        assertThat(cache.get(KEY).get()).as("L1 命中就不该离开进程").isEqualTo("in-l1");
        assertThat(level2.calls).as("L2 一次都不该被读到").isEmpty();
    }

    @Test
    void should_backfill_through_the_typed_get_as_well() {
        level2.put(KEY, "v");

        // get(key, Class) 走的是继承来的 lookup()，回填因此也是本类的代码——不测就等于没测。
        assertThat(cache.get(KEY, String.class)).isEqualTo("v");
        assertThat(cache.getLevel1().get(KEY).get()).isEqualTo("v");
    }

    @Test
    void should_write_through_both_levels_on_put() {
        cache.put(KEY, "v");
        List<String> calls = level2.snapshot();

        // 写必须两层都到：只写 L1 的话，别的实例和本实例重启以后都读不到这条数据。
        assertThat(calls).containsExactly("put");
        assertThat(cache.getLevel1().get(KEY).get()).isEqualTo("v");
        assertThat(cache.getLevel2().get(KEY).get()).isEqualTo("v");
    }

    @Test
    void should_evict_both_levels() {
        cache.put(KEY, "v");

        cache.evict(KEY);
        List<String> calls = level2.snapshot();

        assertThat(calls).containsExactly("put", "evict");
        assertThat(cache.getLevel1().get(KEY)).as("只清 L1 会让旧值在下一次读时从 L2 回填回来").isNull();
        assertThat(cache.getLevel2().get(KEY)).isNull();
    }

    @Test
    void should_clear_both_levels() {
        cache.put(KEY, "v");
        cache.put("other", "w");

        cache.clear();
        List<String> calls = level2.snapshot();

        // clear 是整层清空：只清 L1 的话，L2 的旧值会在下一次读时被回填回来，等于什么都没清掉。
        assertThat(calls).containsExactly("put", "put", "clear");
        assertThat(cache.get(KEY)).isNull();
        assertThat(cache.get("other")).isNull();
    }

    @Test
    void should_report_a_cached_null_as_a_hit() {
        cache.put(KEY, null);

        // 三条一起才叫证据：两层都收到 null、get 回了 wrapper（命中）、wrapper 里的值是 null（业务语义）。
        ValueWrapper hit = cache.get(KEY);
        assertThat(hit).as("缓存了 null 之后不该算未命中").isNotNull();
        assertThat(hit.get()).isNull();
        assertThat(cache.getLevel1().get(KEY)).isNotNull();
        assertThat(cache.getLevel2().get(KEY)).isNotNull();
    }

    @Test
    void should_backfill_a_cached_null_from_level_two_into_level_one() {
        level2.put(KEY, null);

        ValueWrapper hit = cache.get(KEY);

        // 回填链路上把 null 当未命中的话，"挡住穿透"就只剩跨进程那一半，进程内每次还是打到 L2。
        assertThat(hit).isNotNull();
        assertThat(hit.get()).isNull();
        assertThat(cache.getLevel1().get(KEY))
                .as("L1 里必须留下占位符而不是空槽")
                .isNotNull();
        // 存储形态必须是占位符：lookup() 返回解包后的 null 会被上层当成未命中。
        assertThat(cache.getLevel1().get(KEY).get()).isNull();
    }

    @Test
    void should_skip_the_value_loader_when_level_one_already_hits() {
        cache.getLevel1().put(KEY, "in-l1");
        AtomicInteger loaded = new AtomicInteger();

        String value = cache.get(KEY, () -> {
            loaded.incrementAndGet();
            return "computed";
        });

        assertThat(value).isEqualTo("in-l1");
        assertThat(loaded).as("L1 命中还去查库，缓存就等于没开").hasValue(0);
    }

    @Test
    void should_skip_the_value_loader_when_only_level_two_hits_and_backfill() {
        cache.getLevel2().put(KEY, "in-l2");
        AtomicInteger loaded = new AtomicInteger();

        String value = cache.get(KEY, () -> {
            loaded.incrementAndGet();
            return "computed";
        });

        assertThat(value).isEqualTo("in-l2");
        assertThat(loaded).hasValue(0);
        assertThat(cache.getLevel1().get(KEY).get()).isEqualTo("in-l2");
    }

    @Test
    void should_compute_then_store_in_both_levels_when_neither_hits() {
        String value = cache.get(KEY, () -> "computed");

        assertThat(value).isEqualTo("computed");
        assertThat(cache.getLevel1().get(KEY).get()).isEqualTo("computed");
        assertThat(cache.getLevel2().get(KEY).get()).isEqualTo("computed");
    }

    @Test
    void should_not_store_a_computed_null_when_nulls_are_not_cached() {
        TwoLevelCache strict = new TwoLevelCache(NAME, caffeine(false), new RecordingCache(NAME, false), false);
        AtomicInteger loaded = new AtomicInteger();

        String value = strict.get(KEY, () -> {
            loaded.incrementAndGet();
            return null;
        });

        // 查不到是常态而不是错误：这里的"不落层"是拦在 put 之前，而不是让 L1 抛 IllegalArgumentException。
        assertThat(value).isNull();
        assertThat(loaded).hasValue(1);
        assertThat(strict.getLevel1().get(KEY)).isNull();
        assertThat(strict.getLevel2().get(KEY)).isNull();
    }

    @Test
    void should_wrap_a_value_loader_failure_and_store_nothing() {
        IllegalStateException boom = new IllegalStateException("db down");

        assertThatThrownBy(() -> cache.get(KEY, () -> {
            throw boom;
        })).isInstanceOf(ValueRetrievalException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .extracting(Throwable::getCause).isSameAs(boom);

        // 失败不能留下半成品：一次异常查询之后，下一个读者必须重新算而不是拿到一个不存在的值。
        assertThat(cache.get(KEY)).isNull();
        assertThat(level2.calls).doesNotContain("put");
    }

    @Test
    void should_keep_the_existing_level_two_value_on_put_if_absent() {
        cache.getLevel2().put(KEY, "old");
        level2.calls.clear();

        ValueWrapper previous = cache.putIfAbsent(KEY, "new");
        List<String> calls = level2.snapshot();

        assertThat(calls).containsExactly("putIfAbsent");
        assertThat(previous.get()).as("已有值时返回它，而不是静默覆盖").isEqualTo("old");
        assertThat(cache.getLevel2().get(KEY).get()).isEqualTo("old");
        // L1 补齐成一致视图，否则同一个 key 在本进程读到 new、在别的进程读到 old。
        assertThat(cache.getLevel1().get(KEY).get()).isEqualTo("old");
    }

    @Test
    void should_store_and_return_nothing_on_put_if_absent_when_both_levels_are_empty() {
        ValueWrapper previous = cache.putIfAbsent(KEY, "new");
        List<String> calls = level2.snapshot();

        assertThat(calls).containsExactly("putIfAbsent");
        assertThat(previous).as("原本没有值时按 Spring 契约返回 null").isNull();
        assertThat(cache.getLevel1().get(KEY).get()).isEqualTo("new");
        assertThat(cache.getLevel2().get(KEY).get()).isEqualTo("new");
    }

    @Test
    void should_return_the_level_one_value_on_put_if_absent_without_reaching_level_two() {
        cache.getLevel1().put(KEY, "in-l1");
        level2.calls.clear();

        ValueWrapper previous = cache.putIfAbsent(KEY, "new");
        List<String> calls = level2.snapshot();

        assertThat(previous.get()).isEqualTo("in-l1");
        assertThat(calls).as("L1 已有值就不该发起一次网络往返").isEmpty();
        assertThat(cache.getLevel1().get(KEY).get()).isEqualTo("in-l1");
    }

    @Test
    void should_refuse_levels_whose_null_policy_differs() {
        // 两个方向的 mismatch 各一条：只查一层的话，另一层的漏检会表现成"缓存 null 在一层生效、另一层被丢弃"。
        assertThatThrownBy(() -> new TwoLevelCache(NAME, caffeine(false), level2, false))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining(NAME)
                .hasMessageContaining("L1=false")
                .hasMessageContaining("L2=true");

        RecordingCache strictL2 = new RecordingCache(NAME, false);
        assertThatThrownBy(() -> new TwoLevelCache(NAME, caffeine(true), strictL2, true))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("L1=true")
                .hasMessageContaining("L2=false");
    }

    @Test
    void should_cache_a_value_loader_that_found_nothing_when_nulls_are_cached() {
        String value = cache.get(KEY, () -> null);

        // 查不到是常态，而 cache-null-values=true 的意义就是把这条常态挡住两次：
        // 落层（下一次不再打库）与认得出（读回来的是"命中的 null"而不是未命中）。
        assertThat(value).isNull();
        assertThat(cache.getLevel1().get(KEY)).as("L1 要留下占位符").isNotNull();
        assertThat(cache.getLevel2().get(KEY)).as("L2 也要落下占位符").isNotNull();

        AtomicInteger loaded = new AtomicInteger();
        assertThat(cache.get(KEY, () -> {
            loaded.incrementAndGet();
            return "again";
        })).isNull();
        assertThat(loaded).as("已缓存的 null 必须算命中").hasValue(0);
    }

    @Test
    void should_name_the_level_that_mismatches_first() {
        // 上一条款例走的是"L1 一致、L2 不一致"；这条走"L1 就不一致"（|| 在此短路，L2 根本没被读）。
        // 两个方向都钉住，才排得掉"只核对其中一层"这个改法。
        assertThatThrownBy(() -> new TwoLevelCache(NAME, caffeine(true), new RecordingCache(NAME, false), false))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("L1=true");
    }

    @Test
    void should_expose_its_name_both_levels_and_the_native_caches() {
        assertThat(cache.getName()).isEqualTo(NAME);

        Object nativeCache = cache.getNativeCache();
        assertThat(nativeCache).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> levels = (Map<String, Object>) nativeCache;
        assertThat(levels).containsOnlyKeys("l1", "l2");
        // 两级没有任何一层能单独代表"原生缓存"，所以给的是两层的组合；诊断脚本走 getLevel1()/getLevel2()。
        assertThat(levels.get("l1")).isSameAs(cache.getLevel1().getNativeCache());
        assertThat(levels.get("l2")).isSameAs(level2.getNativeCache());
        assertThat(cache.getLevel1()).isSameAs(level1);
        assertThat(cache.getLevel2()).isSameAs(level2);
    }

    /** L1 用真 Caffeine：与 {@code CaffeineLevelFactory} 同一条产出路径，null 占位符走 Spring 自己的代码。 */
    private static CaffeineCache caffeine(boolean allowNullValues) {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setAllowNullValues(allowNullValues);
        return (CaffeineCache) manager.getCache(NAME);
    }

    /**
     * L2 的记录型替身：真实存储（{@code LinkedHashMap}，保持插入序便于读日志）+ 一条有序调用日志。
     * 它继承 {@code AbstractValueAdaptingCache}，因此 null 占位符走的仍是 Spring 自己的代码。
     */
    private static final class RecordingCache extends AbstractValueAdaptingCache {

        private final String name;
        private final Map<Object, Object> store = new LinkedHashMap<>();
        private final List<String> calls = new ArrayList<>();

        /** 快照：断言里对这一层的读取同样会记进日志，所以先取快照再断言。 */
        List<String> snapshot() {
            return List.copyOf(calls);
        }

        RecordingCache(String name, boolean allowNullValues) {
            super(allowNullValues);
            this.name = name;
        }

        @Override
        protected Object lookup(Object key) {
            calls.add("get");
            return store.get(key);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Object getNativeCache() {
            return store;
        }

        @Override
        public void put(Object key, Object value) {
            calls.add("put");
            store.put(key, toStoreValue(value));
        }

        @Override
        public <T> T get(Object key, Callable<T> valueLoader) {
            // 加载版 get 由 TwoLevelCache 自己实现：读到这里说明被测代码跑偏去委托单层加载了。
            calls.add("get(callable)");
            throw new IllegalStateException("RecordingCache 不承载 valueLoader 形态的读取");
        }

        @Override
        public ValueWrapper putIfAbsent(Object key, Object value) {
            calls.add("putIfAbsent");
            Object existing = store.get(key);
            if (existing != null) {
                return toValueWrapper(existing);
            }
            store.put(key, toStoreValue(value));
            return null;
        }

        @Override
        public void evict(Object key) {
            calls.add("evict");
            store.remove(key);
        }

        @Override
        public void clear() {
            calls.add("clear");
            store.clear();
        }
    }
}
