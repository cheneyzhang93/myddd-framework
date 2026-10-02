package org.deep361.myddd.cache.composite;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.AbstractValueAdaptingCache;

/**
 * 把 L1 与 L2 两个 {@link CacheManager} 组成一个"每个名字两级都看"的管理器。
 *
 * <p><b>为什么不能直接用 Spring 的组合管理器</b>：{@code CompositeCacheManager} 是 first-match——
 * 只要 L1 认得这个名字就返回 L1 的缓存，L2 永远不会被读到（设计原因见 {@link TwoLevelCache} 的类注释）。
 *
 * <p><b>两级必须同时在场</b>：任一管理器对这个名字给不出缓存，本管理器就返回 {@code null}（Spring 的契约，
 * {@code @Cacheable} 随之退化为不缓存）。我们不去造"只有 L2 的两级缓存"——那会让同一个缓存名在不同实例上
 * 表现不同，而配置正确时这条路不可达：两层的白名单由装配处用同一份 {@code myddd.cache.cache-names} 配平。
 *
 * <p>两层的 {@link CacheManager} 由装配处构造并<b>不</b>注册成 bean：容器里同时存在三个
 * {@code CacheManager} 时，{@code @Cacheable} 的按类型注入要靠 {@code @Primary} 才不歧义，
 * 而"框架自己的两级内部件"没有理由出现在业务的 bean 图里。诊断入口是 {@link #getLevel1()}
 * 与 {@link #getLevel2()}。
 */
public class TwoLevelCacheManager implements CacheManager {

    private final CacheManager level1;
    private final CacheManager level2;
    private final boolean allowNullValues;
    private final Map<String, TwoLevelCache> caches = new ConcurrentHashMap<>();

    public TwoLevelCacheManager(CacheManager level1, CacheManager level2, boolean allowNullValues) {
        if (level1 == null || level2 == null) {
            throw new FrameworkException("两级缓存管理器需要两个非空的层级管理器，实得 L1=" + level1
                    + "、L2=" + level2);
        }
        this.level1 = level1;
        this.level2 = level2;
        this.allowNullValues = allowNullValues;
    }

    /** L1 那一层（进程内 Caffeine）。 */
    public CacheManager getLevel1() {
        return level1;
    }

    /** L2 那一层（Redis）。 */
    public CacheManager getLevel2() {
        return level2;
    }

    /**
     * 同一个名字始终返回<b>同一个</b>实例：{@code @Cacheable(sync=true)} 与自建的两级视图必须落在同一个
     * L1/L2 对上，否则两个包装对象各自维护回填逻辑，读到的是两份不同的 L1 状态。
     *
     * <p>任何一层给不出缓存时返回 {@code null}（Spring 的契约：{@code null} 表示"没有这个缓存"，
     * {@code @Cacheable} 随之退化为不缓存）。这在配置正确时不可达——两层的白名单由装配处用同一份
     * {@code myddd.cache.cache-names} 配平——所以它是防御而不是特性。
     */
    @Override
    public Cache getCache(String name) {
        Cache first = level1.getCache(name);
        if (first == null) {
            return null;
        }
        Cache second = level2.getCache(name);
        if (second == null) {
            return null;
        }
        AbstractValueAdaptingCache l1 = requireAdapting(name, first, "L1");
        AbstractValueAdaptingCache l2 = requireAdapting(name, second, "L2");
        return caches.computeIfAbsent(name, key -> new TwoLevelCache(key, l1, l2, allowNullValues));
    }

    /**
     * Spring 7 的 {@link Cache} 接口没有 {@code isAllowNullValues()}（javap 实测），null 策略只在
     * {@link AbstractValueAdaptingCache} 上可读。读不到就没法保证"缓存 null"在两层一致——那正是
     * {@code cache-null-values=true} 唯一剩下的那半效果，所以这里拒绝组合而不是赌它一致。
     */
    private static AbstractValueAdaptingCache requireAdapting(String name, Cache cache, String layer) {
        if (!(cache instanceof AbstractValueAdaptingCache adapting)) {
            throw new FrameworkException("缓存 " + name + " 的 " + layer + " 实现是 "
                    + cache.getClass().getName() + "，它不继承 AbstractValueAdaptingCache，"
                    + "因此读不到它的 null 策略，两级组合无法保证 cache-null-values 在两层一致。"
                    + "请让该层用 AbstractValueAdaptingCache 的子类（Caffeine / Redis / ConcurrentMap 的实现都是）");
        }
        return adapting;
    }

    /**
     * 两层的名字并集，稳定排序（{@link LinkedHashSet}：先 L1 后 L2，各自内部保持各自的顺序）。
     *
     * <p>取并集而不是只取 L1：白名单为空时两层的"已创建名字"都可能是空的，而任一层被外部预热过的名字
     * 都该被看见——漏报会让运维以为某个缓存名没生效。
     */
    @Override
    public Collection<String> getCacheNames() {
        Set<String> names = new LinkedHashSet<>(level1.getCacheNames());
        names.addAll(level2.getCacheNames());
        return names;
    }
}
