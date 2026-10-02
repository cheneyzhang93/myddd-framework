package org.deep361.myddd.cache.composite;

import java.util.Map;
import java.util.concurrent.Callable;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.springframework.cache.Cache;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.cache.support.NullValue;

/**
 * 两级缓存：先读 L1（进程内），未命中读 L2 并<b>回填 L1</b>；写、失效、清空同时落两层。
 *
 * <p><b>为什么自己写</b>：Spring 唯一的组合管理器
 * {@code org.springframework.cache.support.CompositeCacheManager} 是 first-match 语义——按顺序找到第一个
 * 含该名字的缓存就直接返回，永远不会把第二个管理器当后备存储（本机 javap + 语义实测，见 Plan 3 §0.1）。
 * 把 Caffeine 与 Redis 两个管理器交给它，结果就是"永远命中 L1、L2 形同虚设"。
 *
 * <p><b>null 的三段语义按 Spring 7 的 {@link #get(Object)} 契约实现</b>：返回 {@code null} 是"未命中"，
 * 返回一个 {@code get()} 为 {@code null} 的 {@link ValueWrapper} 是"命中了一个缓存下来的 null"。
 * 两层的 {@code AbstractValueAdaptingCache} 各自负责占位符（L1 是 {@code NullValue}，L2 是 Redis 侧的
 * {@code BINARY_NULL_VALUE}），本类只在解包后的值上做搬运，因此不翻译占位符、也就不会翻译错。
 *
 * <p><b>已知边界（刻意如此，不是待办）</b>：
 * <ul>
 *   <li><b>跨节点陈旧上界 = L1 的 {@code expire-after-write}</b>。没有失效广播，别的实例改了 L2，本实例
 *       已回填的 L1 条目最长还会用这么久。要更强的一致就把 L1 的过期调短，或改 {@code type=caffeine}
 *       之外的方案（Plan 3 不含 pub/sub 失效总线，见 §1.2.1 键表——它一个键都没为那个方案预留）。</li>
 *   <li>{@link #get(Object, Callable)} 与 {@link #putIfAbsent(Object, Object)} 在<b>跨层</b>不原子：
 *       并发下 valueLoader 可能被调用多次（Redis 侧的原子性仍由 L2 的 {@code putIfAbsent} 提供，
 *       见该方法实现）。这是两级缓存的固有代价，Spring 自己的 Redis 实现在单层上也一样。</li>
 * </ul>
 */
public class TwoLevelCache extends AbstractValueAdaptingCache {

    private final String name;
    private final AbstractValueAdaptingCache level1;
    private final AbstractValueAdaptingCache level2;

    /**
     * @param name            缓存名，两层共用同一个名字
     * @param level1          进程内一层
     * @param level2          分布式一层
     * @param allowNullValues 两层的 {@code isAllowNullValues()} 必须与此相同；不一致会让"缓存 null"在其中
     *                        一层静默失效，因此构造期即拒绝而不是留到一个看不懂的命中率为零上。
     *                        参数类型收成 {@link AbstractValueAdaptingCache} 正是为了让这件事在类型上成立：
     *                        Spring 7 的 {@code Cache} 接口<b>没有</b> {@code isAllowNullValues()}（javap 实测），
     *                        拿 {@code Cache} 当参数就无从核对（代价见 {@link TwoLevelCacheManager} 的同类说明）
     */
    public TwoLevelCache(String name, AbstractValueAdaptingCache level1, AbstractValueAdaptingCache level2,
            boolean allowNullValues) {
        super(allowNullValues);
        if (level1.isAllowNullValues() != allowNullValues || level2.isAllowNullValues() != allowNullValues) {
            throw new FrameworkException("缓存 " + name + " 的 null 策略不一致：组合层要求 allowNullValues="
                    + allowNullValues + "，而 L1=" + level1.isAllowNullValues() + "、L2="
                    + level2.isAllowNullValues() + "。不一致的后果是"
                    + "“缓存 null 在一层生效、在另一层被丢弃”，一个只体现在命中率上的差异");
        }
        this.name = name;
        this.level1 = level1;
        this.level2 = level2;
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * 两层的原生缓存，按 {@code {l1=…, l2=…}} 给出（Spring 的契约只要求"底层缓存的原生对象"，
     * 两级时没有任何一层能单独代表它）。诊断脚本请走 {@link #getLevel1()} / {@link #getLevel2()}。
     */
    @Override
    public Object getNativeCache() {
        return Map.of("l1", level1.getNativeCache(), "l2", level2.getNativeCache());
    }

    /** 诊断与测试用的入口：L1 那一层。 */
    public Cache getLevel1() {
        return level1;
    }

    /** 诊断与测试用的入口：L2 那一层。 */
    public Cache getLevel2() {
        return level2;
    }

    /**
     * 返回的是<b>存储形态</b>的值（缓存下来的 null 以 {@link NullValue#INSTANCE} 给出），这是
     * {@link AbstractValueAdaptingCache} 的契约：上层 {@code get(key)} 靠"非 null 的存储值"区分
     * "命中一个 null"与"未命中"，返回解包后的 {@code null} 会把前者当成后者，于是
     * {@code cache-null-values=true} 只剩"跨进程不重复查库"这一半效果、进程内那一半静默失效。
     */
    @Override
    protected Object lookup(Object key) {
        ValueWrapper hit = level1.get(key);
        if (hit == null) {
            hit = level2.get(key);
            if (hit == null) {
                return null;
            }
            // 回填 L1：put 会按 L1 自己的 null 策略决定是否换成占位符
            level1.put(key, hit.get());
        }
        return hit.get() == null ? NullValue.INSTANCE : hit.get();
    }

    @Override
    public void put(Object key, Object value) {
        level1.put(key, value);
        level2.put(key, value);
    }

    @Override
    public void evict(Object key) {
        level1.evict(key);
        level2.evict(key);
    }

    @Override
    public void clear() {
        level1.clear();
        level2.clear();
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        ValueWrapper known = level1.get(key);
        if (known != null) {
            return known;
        }
        // 让 L2 决定谁赢：它在 Redis 侧是原子的。赢家若是 L2 上已有的值，就把 L1 补齐成一致视图。
        ValueWrapper stored = level2.putIfAbsent(key, value);
        level1.put(key, stored == null ? value : stored.get());
        return stored;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper hit = level1.get(key);
        if (hit == null) {
            hit = level2.get(key);
            if (hit != null) {
                level1.put(key, hit.get());
            }
        }
        if (hit != null) {
            return (T) hit.get();
        }
        T computed;
        try {
            computed = valueLoader.call();
        } catch (Exception ex) {
            throw new ValueRetrievalException(key, valueLoader, ex);
        }
        // 算出来是 null 且不允许缓存 null 时不落层：这一层语义与单层实现（CaffeineCache / RedisCache）
        // 一致——它们对这种 put 分别是"抛 IllegalArgumentException"与"静默跳过"，
        // 而 valueLoader 返回 null 是常态（查不到），所以在这里拦掉而不是让 L1 为一件正常的事报错。
        if (computed != null || isAllowNullValues()) {
            put(key, computed);
        }
        return computed;
    }
}
