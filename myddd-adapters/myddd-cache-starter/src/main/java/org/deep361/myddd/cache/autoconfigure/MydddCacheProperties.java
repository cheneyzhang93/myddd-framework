package org.deep361.myddd.cache.autoconfigure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.annotation.PostConstruct;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 缓存能力配置（前缀 {@code myddd.cache}）。
 *
 * <p><b>本类只声明"有代码去读"的键</b>：一个没人读的键会制造最难排查的一类故障——业务改了配置、启动不报错、
 * 行为一点没变。Spring Cache 自身的键（{@code spring.cache.*}）不代理、不改名（§0.3-6）：我们只接管
 * CacheManager 这一个 bean 的装配。L2 的连接参数同样不代理（{@code spring.data.redis.*} 是 Boot 的键，
 * 由 {@code DataRedisAutoConfiguration} 消费），本类只负责"缓存怎么用这个连接"。
 *
 * <p><b>关于 {@code caffeine.max-size} 与缓存名个数</b>：Caffeine 的上限是<b>每个缓存各自一份</b>，
 * 不是全局共享。所以"随便起个缓存名"在进程内的代价是 1000 条目的独立空间，而不是挤占别人的额度。
 * {@code cache-names} 因此存在：它把可缓存的名字收成一份白名单，代价是名单外的名字<b>拿不到缓存</b>
 * （见 {@link #getCacheNames()} 的说明），这是一次明确的取舍，不是两种都想要的含糊。
 */
@ConfigurationProperties("myddd.cache")
public class MydddCacheProperties {

    /**
     * 缓存形态。
     *
     * <p>缺省是 {@link #MULTI}（设计文档 §1.2.1 的终态缺省）。选 {@link #CAFFEINE} 的唯一理由是"这个进程不接
     * Redis"——它不需要 {@code RedisConnectionFactory} bean，也就不会在缺连接时启动失败。
     */
    public enum Type {

        /** 只有进程内 Caffeine：单机、无跨节点共享，适合字典表与短 TTL 的读多写少数据。 */
        CAFFEINE,

        /** Caffeine L1 + Redis L2：读时 L2 回填 L1，写与失效同时落两层。 */
        MULTI
    }

    /** 缓存适配层总开关。关闭时本模块不装配任何 bean，配置校验同样跳过。 */
    private boolean enabled = true;

    /** 缺省 {@code multi}：两级缓存是本能力的交付形态，{@code caffeine} 是给不接 Redis 的进程的退路。 */
    private Type type = Type.MULTI;

    /**
     * L2 键的前缀段（{@code myddd:cache}）。
     *
     * <p>Redis 常常多个应用共用一个实例，前缀段就是"哪些键属于本应用"的唯一凭据，也是
     * {@code redis-cli --scan --pattern} 能圈定范围的前提。{@code type=multi} 时空白值会被
     * {@code validate()} 拒掉而不是
     * 当成"不加前缀"——忘了配和不想配是两件事，而本框架<b>不提供</b>裸缓存名的取值：共享实例上
     * {@code order::1} 这种键会和别人的键互撞。确实不需要 L2 的进程请改 {@code myddd.cache.type=caffeine}。
     */
    private String keyPrefix = "myddd:cache";

    /** 是否缓存 {@code null}。默认关闭：宁可对同一个空结果多打一次库，也不要"新增数据在 TTL 内不可见"。 */
    private boolean cacheNullValues = false;

    /**
     * 缓存名白名单。空（默认）＝按名字动态创建，与 Spring Cache 的缺省行为一致；
     * 非空＝只有名单内的名字会获得缓存，名单外 {@code getCache} 返回 {@code null}，
     * {@code @Cacheable} 随之<b>静默不缓存</b>。所以一旦给出名单，就要把业务里用到的名字写全。
     *
     * <p>白名单同时作用到 L1 与 L2（L2 侧关掉"按名字动态创建"），两级不一致会让名单外的名字
     * 在 L1 拿不到、在 L2 却能建，组合管理器就无从判断该返回 {@code null} 还是返回一个只有 L2 的缓存。
     */
    private final List<String> cacheNames = new ArrayList<>();

    private final Caffeine caffeine = new Caffeine();

    private final Redis redis = new Redis();

    /** L1（进程内 Caffeine）的取值。 */
    public static class Caffeine {

        /** 单个缓存的条目上限。 */
        private long maxSize = 1000;

        /**
         * 写后过期。L1 只负责"挡掉短时间内的重复读"；<b>它也是跨节点陈旧的上界</b>——
         * 别的实例改了 L2，本实例的 L1 最长还会用 {@code expireAfterWrite} 这么久（无失效广播，见装配类 javadoc）。
         */
        private Duration expireAfterWrite = Duration.ofMinutes(10);

        public long getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(long maxSize) {
            this.maxSize = maxSize;
        }

        public Duration getExpireAfterWrite() {
            return expireAfterWrite;
        }

        public void setExpireAfterWrite(Duration expireAfterWrite) {
            this.expireAfterWrite = expireAfterWrite;
        }
    }

    /** L2（Redis）的取值。连接本身用 Boot 的 {@code spring.data.redis.*}，本段只写"缓存怎么用"。 */
    public static class Redis {

        /** L2 的缺省过期。必须配：没有 TTL 的 Redis 缓存就是"删掉数据后重启应用，脏数据还在"。 */
        private Duration ttl = Duration.ofMinutes(30);

        /**
         * 按缓存名覆盖 {@link #ttl}。键是缓存名（如 {@code productDetail}），值必须为正时长。
         *
         * <p>缓存名里带 {@code .} 时要用方括号写：{@code myddd.cache.redis.ttl-per-cache.[a.b]=1h}
         * （Spring 的 relaxed binding 规则，不加括号会被当成更深的属性路径）。
         */
        private final Map<String, Duration> ttlPerCache = new LinkedHashMap<>();

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public Map<String, Duration> getTtlPerCache() {
            return ttlPerCache;
        }
    }

    /**
     * 启动期校验。L1 的键两种形态都要查（{@code multi} 也带着 Caffeine），L2 的键只在
     * {@code type=multi} 时查：{@code caffeine} 形态下没有任何代码读它们，为一个不生效的键判应用起不来
     * 比让它稍后在切回 {@code multi} 时报错更糟。
     */
    @PostConstruct
    public void validate() {
        if (!enabled) {
            return;
        }
        if (type == null) {
            throw new FrameworkException("myddd.cache.type 不能为空：它决定装配哪一种 CacheManager，"
                    + "可取值 caffeine 与 multi，省略则用缺省值 multi");
        }
        Caffeine spec = getCaffeine();
        if (spec.getMaxSize() < 1) {
            throw new FrameworkException("myddd.cache.caffeine.max-size=" + spec.getMaxSize()
                    + " 非法：0 或负数不会报错，只会让 L1 永远存不下任何东西，"
                    + "缓存看起来开着却一次也没命中；请给出正整数，或关闭 myddd.cache.enabled");
        }
        Duration expire = spec.getExpireAfterWrite();
        if (expire == null || expire.isZero() || expire.isNegative()) {
            throw new FrameworkException("myddd.cache.caffeine.expire-after-write=" + expire
                    + " 非法：零或负数的过期时间等于条目一写进 L1 就蒸发，"
                    + "而 Caffeine 对它是静默接受而不是报错；请给出正的时长（如 10m），"
                    + "不想用进程内缓存请关闭 myddd.cache.enabled");
        }
        if (type != Type.MULTI) {
            return;
        }
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new FrameworkException("myddd.cache.key-prefix 不能是空白：它是 Redis 里"
                    + "“哪些键属于本应用”的唯一凭据，共享实例上少一段前缀就会读到别人的键。"
                    + "确实不需要 L2 请写 myddd.cache.type=caffeine");
        }
        Redis l2 = getRedis();
        Duration ttl = l2.getTtl();
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new FrameworkException("myddd.cache.redis.ttl=" + ttl
                    + " 非法：零、负数或 null 会让 L2 的键永不过期，"
                    + "于是删掉的数据在重启应用之后仍然从缓存读回来。请给出正的时长（如 30m）");
        }
        l2.getTtlPerCache().forEach((name, override) -> {
            if (name.isBlank()) {
                throw new FrameworkException("myddd.cache.redis.ttl-per-cache 的键不能是空白："
                        + "空白键永远匹配不到任何缓存名，只会静默生效不了");
            }
            if (override == null || override.isZero() || override.isNegative()) {
                throw new FrameworkException("myddd.cache.redis.ttl-per-cache." + name + "=" + override
                        + " 非法：与 myddd.cache.redis.ttl 同理，非正时长会让这个缓存的键永不过期。"
                        + "要么给出正的时长，要么删掉这条覆盖让它跟随缺省值");
            }
        });
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public boolean isCacheNullValues() {
        return cacheNullValues;
    }

    public void setCacheNullValues(boolean cacheNullValues) {
        this.cacheNullValues = cacheNullValues;
    }

    public List<String> getCacheNames() {
        return cacheNames;
    }

    public Caffeine getCaffeine() {
        return caffeine;
    }

    public Redis getRedis() {
        return redis;
    }
}
