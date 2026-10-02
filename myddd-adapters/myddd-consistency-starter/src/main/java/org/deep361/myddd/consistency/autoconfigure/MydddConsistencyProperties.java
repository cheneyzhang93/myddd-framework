package org.deep361.myddd.consistency.autoconfigure;

import java.time.Duration;
import jakarta.annotation.PostConstruct;
import org.deep361.myddd.consistency.idempotent.JdbcIdempotentStore;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 一致性能力配置（前缀 {@code myddd.consistency}）。
 *
 * <p><b>本类只声明"有代码去读"的键</b>（与缓存适配器同一条纪律）：没人读的键会制造最难排查的一类故障——
 * 业务改了配置、启动不报错、行为一点没变。Redis 的连接参数不在这里（{@code spring.data.redis.*} 是 Boot 的键，
 * 由 {@code DataRedisAutoConfiguration} 解析成 {@code DataRedisConnectionDetails}），本类只负责
 * "锁怎么用这个连接、锁键长什么样"，以及"幂等用什么存储、占位键长什么样"。
 *
 * <p><b>{@code lock.watchdog-timeout} 是客户端侧的续租节拍，不是锁的持有时长</b>：只有
 * {@code leaseTime == 0}（见 R3：业务显式要 watchdog）时它才起作用——Redisson 每隔这个时长的三分之一
 * 给还持锁的线程续一次租约。{@code leaseTime > 0}（含 {@code LockPort} 的缺省 30s）时它是<b>完全无关</b>的
 * 数字，租约到点就释放、不续租。把它读成"锁最长持有多久"会让业务以为长任务有保护。
 */
@ConfigurationProperties("myddd.consistency")
public class MydddConsistencyProperties {

    /** 一致性适配层总开关。关闭时本模块不装配任何 bean，配置校验同样跳过。 */
    private boolean enabled = true;

    private final Lock lock = new Lock();

    private final Idempotent idempotent = new Idempotent();

    /** 分布式锁（{@code LockPort} 与 {@code @DistributedLock}）的取值。 */
    public static class Lock {

        /** 锁能力开关。关闭时不装配 {@code RedissonClient} 与 {@code LockPort}，也不校验下面的键。 */
        private boolean enabled = true;

        /**
         * 锁键前缀段（{@code myddd:lock}）。
         *
         * <p>Redis 常多个应用共用一个实例，前缀段就是"哪些锁属于本应用"的唯一凭据，也是
         * {@code redis-cli --scan --pattern 'myddd:lock:*'} 能圈定范围的前提。空白值会被 {@code validate()}
         * 拒掉而不是当成"不加前缀"：共享实例上 {@code order:1} 这种裸键会和别人的锁互撞，而锁互撞的表现为
         * "偶尔拿不到锁"，比启动失败难查两个数量级。
         */
        private String keyPrefix = "myddd:lock";

        /**
         * Redisson watchdog 的续租节拍（30s，与 Redisson 自身的缺省值一致）。
         *
         * <p>只在 {@code leaseTime == 0} 的形态下生效（R3）。非正值会让续租节拍变成一个"每次都要立刻续租"
         * 或"永不续租"的循环——而业务选 watchdog 的理由恰恰是"我不知道这个任务要跑多久"。
         */
        private Duration watchdogTimeout = Duration.ofSeconds(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public Duration getWatchdogTimeout() {
            return watchdogTimeout;
        }

        public void setWatchdogTimeout(Duration watchdogTimeout) {
            this.watchdogTimeout = watchdogTimeout;
        }
    }

    /**
     * 幂等能力（{@code IdempotentPort} 与 {@code @Idempotent}）的取值。
     *
     * <p>这一段与 {@link Lock} 是<b>两个独立的能力</b>，不是"锁的另一种用法"：锁管"同一时刻只有一个在跑"，
     * 幂等管"同一件事只做一次"。它们的存储也不同——锁必须走 Redisson（可重入、看门狗、持有者校验都要
     * 客户端配合），幂等只需要一条 {@code SET NX} 与一段 Lua，因此走 {@code StringRedisTemplate}。
     * 这就是"关掉锁之后幂等依然可用"（见 {@code MydddConsistencyAutoConfiguration} 的失败话术）成立的原因。
     */
    public static class Idempotent {

        /**
         * 占位存储。
         *
         * <p>取值由 Spring 的枚举绑定校验：写 {@code store=mysql} 会在<b>绑定阶段</b>就失败并列出候选值，
         * 所以这里不需要"未知值"的自定义话术——而装配侧两条 {@code @ConditionalOnProperty} 各认一个值，
         * 若是 String 字段就会得到"配了第三种值 → 谁都不装 → 注解静默失效"的形状（与
         * {@code MydddCacheProperties.Type} 同一条裁定）。
         */
        public enum Store {

            /** Redis：{@code SET key token NX PX} 占位 + Lua 比对 token 后删除（缺省）。 */
            REDIS,

            /** 数据库：{@code myddd_idempotent_record} 表的唯一键插入占位，适用于没有 Redis、
             * 或幂等记录必须与业务同库可查的场景。表名由 {@link Db#getTable()} 指定。 */
            DB
        }

        /**
         * 框架的幂等实现开关：关闭时不装配 {@code IdempotentPort}，配置校验同样跳过这一段。
         *
         * <p><b>它不摘切面</b>，与 {@link Lock#isEnabled()} 同一条语义：{@code @Idempotent} 照旧被拦截，
         * 容器里没有端口时第一次调用报出缺什么。把注解变成"标了没人读"是幂等最坏的失效形态，
         * 而这条开关管不着它。业务自带 {@code IdempotentPort} 时不必打开这个开关。
         */
        private boolean enabled = true;

        /**
         * 幂等键前缀段（{@code myddd:idempotent}）。
         *
         * <p>与锁的前缀段同一条理由：Redis 常多应用共用实例，前缀段是"哪些占位属于本应用"的唯一凭据。
         * 空白值被 {@code validate()} 拒掉而不是当成"不加前缀"——共享实例上 {@code order:1} 这种裸键
         * 会撞上别人的键，而撞上的表现是"这个接口偶尔报重复提交"，比启动失败难查得多。
         */
        private String keyPrefix = "myddd:idempotent";

        /** 缺省 {@link Store#REDIS}：绝大多数应用已经有 Redis，且 Redis 侧天然带 TTL。 */
        private Store store = Store.REDIS;

        /**
         * {@code store=db} 那一支的表参数。与 {@code redis.*} 只在 {@code type=multi} 时校验同一条裁定：
         * 这一段的形状校验也推迟到 {@code JdbcIdempotentStore} 的构造器（表名要拼进 SQL 文本，那里是唯一
         * 能一次管住两种存储选择的落点），所以 {@code validate()} 不读它——选了 redis 的应用不该为
         * 一个用不到的键起不来。
         */
        private final Db db = new Db();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public Store getStore() {
            return store;
        }

        public void setStore(Store store) {
            this.store = store;
        }

        public Db getDb() {
            return db;
        }

        /**
         * 幂等占位表（{@code store=db} 时使用）。
         *
         * <p>刻意只有表名一个键：列名与列宽由框架交付的参考 DDL 定死（{@code myddd-persistence-starter}
         * 的 {@code db/ddl-reference/V2__myddd_idempotent_record.sql}），"改列名"不是配置项而是换实现。
         */
        public static class Db {

            /**
             * 表名，缺省 {@link JdbcIdempotentStore.DEFAULT_TABLE}。
             *
             * <p>取值必须匹配 {@code [a-z0-9_]{1,64}}：它会原样拼进 SQL 文本，而这是这条链上唯一的
             * 字符串拼接注入面。校验点在构造器（{@code IdempotentStoreGuards#requireTableName}），
             * 非法值以启动期失败收场，不做静默修正。
             */
            private String table = JdbcIdempotentStore.DEFAULT_TABLE;

            public String getTable() {
                return table;
            }

            public void setTable(String table) {
                this.table = table;
            }
        }
    }

    /**
     * 启动期校验。跳过条件与缓存适配器一致：总开关关了就没有任何代码读这些键。
     *
     * <p><b>锁与幂等各查各的，一条链的关闭不能顶掉另一条链的校验</b>：两个能力共用这份配置，
     * 若沿用"锁关了就直接 return"的形状，{@code lock.enabled=false} 的应用就再也得不到
     * "幂等前缀写成了空白"这句话——那正是最难查的一类组合（两个开关各管一半、错误互相遮蔽）。
     *
     * <p>每一段只在自身 {@code enabled=true} 时校验——关掉能力以后为一个不生效的键判应用起不来，
     * 比让它稍后在切回 {@code true} 时报错更糟（这是 {@code MydddCacheProperties} 里 {@code redis.*}
     * 只在 {@code type=multi} 时校验的同一条裁定）。
     */
    @PostConstruct
    public void validate() {
        if (!enabled) {
            return;
        }
        validateLock();
        validateIdempotent();
    }

    private void validateLock() {
        Lock lock = getLock();
        if (!lock.isEnabled()) {
            return;
        }
        if (lock.getKeyPrefix() == null || lock.getKeyPrefix().isBlank()) {
            throw new FrameworkException("myddd.consistency.lock.key-prefix 不能是空白：它是 Redis 里"
                    + "“哪些锁属于本应用”的唯一凭据，共享实例上裸锁名会和别人的锁互撞，而那种故障长成"
                    + "“偶尔拿不到锁”的样子。确实不需要分布式锁请写 myddd.consistency.lock.enabled=false");
        }
        Duration watchdog = lock.getWatchdogTimeout();
        if (watchdog == null || watchdog.isZero() || watchdog.isNegative()) {
            throw new FrameworkException("myddd.consistency.lock.watchdog-timeout=" + watchdog
                    + " 非法：零或负数会让 watchdog 的续租节拍变成一个永不续租的循环，"
                    + "而业务要 watchdog 的理由恰恰是“任务时长不可预知”——静默不续租会让锁在任务跑完之前释放，"
                    + "两个线程同时进临界区且不报错。请给出正的时长（如 30s），"
                    + "不续租的有界租约才是缺省语义（leaseTime > 0，见 R3）");
        }
    }

    private void validateIdempotent() {
        Idempotent idempotent = getIdempotent();
        if (!idempotent.isEnabled()) {
            return;
        }
        if (idempotent.getKeyPrefix() == null || idempotent.getKeyPrefix().isBlank()) {
            throw new FrameworkException("myddd.consistency.idempotent.key-prefix 不能是空白：它是 Redis 里"
                    + "“哪些占位属于本应用”的唯一凭据，共享实例上裸键名会和别人的占位互撞，而那种故障长成"
                    + "“这个接口偶尔报重复提交”的样子。确实不需要框架交付占位存储请写 "
                    + "myddd.consistency.idempotent.enabled=false（关的是实现，@Idempotent 注解照旧被拦截）");
        }
        if (idempotent.getStore() == null) {
            throw new FrameworkException("myddd.consistency.idempotent.store 不能为空：它决定装配哪一种占位存储"
                    + "（redis 走 SET NX + Lua，db 走唯一键插入），空值等于两条链都不装而注解照旧生效；"
                    + "请填写 redis 或 db；不想要框架交付占位存储请写 "
                    + "myddd.consistency.idempotent.enabled=false（关的是实现，@Idempotent 注解照旧被拦截）");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Lock getLock() {
        return lock;
    }

    public Idempotent getIdempotent() {
        return idempotent;
    }
}
