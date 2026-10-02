package org.deep361.myddd.consistency.lock;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.deep361.myddd.consistency.autoconfigure.MydddConsistencyProperties;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.port.consistency.LockPort;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/**
 * {@link LockPort} 的 Redisson 实现：把"拿锁—执行—释放"收成一次方法调用。
 *
 * <p><b>R3 的两条时序在这里分叉，这是本类唯一的设计核心</b>：
 * <ul>
 *   <li>{@code leaseTime > 0}（含 {@link LockPort} 缺省的 30s）：{@code tryLock(wait, lease, MILLIS)}。
 *       租约到点<b>自动释放且不续租</b>——Redisson 在这个形态下不启动 watchdog。这是缺省语义：
 *       宁可任务超时后失去互斥，也不让一个崩溃的线程把锁占到天荒地老。</li>
 *   <li>{@code leaseTime == 0}：{@code tryLock(wait, MILLIS)}（{@code java.util.concurrent.locks.Lock}
 *       继承来的那个重载）。Redisson 于是启动 watchdog，按
 *       {@code myddd.consistency.lock.watchdog-timeout} 的节拍给还持锁的线程续租，直到显式 {@code unlock}
 *       或客户端关闭。业务要这个形态必须<b>显式传 0</b>，传错方向的表现是"长任务跑到一半锁没了"。</li>
 * </ul>
 *
 * <p><b>{@code leaseTime} 为负一律拒绝。</b>内核 {@link LockPort} 的 javadoc 写的是"必须为正数"，
 * 而本框架按 §0.5 的 R3 裁定把 {@code 0} 用作 watchdog 的<b>哨兵值</b>（不是"零租约"）——
 * 那条 javadoc 早于裁定成文，且内核侧没有任何运行期校验，所以这里落地的是"非负即可，负数拒绝"。
 * 这是一处有意的、已记入偏差记录的偏离，不是遗漏。
 *
 * <p><b>释放前先看 {@code isHeldByCurrentThread()}（一手实测的必要）</b>：Redisson 的 {@code unlock()}
 * 在不持锁时抛 {@code IllegalMonitorStateException}。而"动作跑完时锁已经因租约到期被释放"是 R3 第一条形态下
 * 的<b>正常后果</b>（任务比 leaseTime 长）。无条件 {@code unlock} 会把一个已经成功的业务动作改成抛错，
 * 还把"锁早已不在我手上"这件事实掩盖成"释放失败"。反过来，跳过 {@code unlock} 也不会误删别人的锁：
 * 那个线程刚拿到租约，本线程无权动它。
 *
 * <p><b>可重入由 Redisson 保证</b>（同一线程 {@code getLock(同名)} 拿到的是同一个可重入锁，
 * {@code getHoldCount()} 递增），本类不加本地线程计数：本地计数与 Redis 侧的计数会各走各的，
 * 出现"本地以为还持锁、Redis 已经过期"的更坏形状。
 *
 * <p><b>拿不到锁抛 {@link CommonErrorCode#LOCK_ACQUIRE_FAILED}</b>（HTTP 409）而不是返回 {@code null}：
 * 端口契约要求模板方法要么执行动作要么失败，静默返回会让调用方把"没执行"当成"执行了但没结果"。
 */
public class DefaultLockPort implements LockPort {

    private final RedissonClient client;
    private final String keyPrefix;

    public DefaultLockPort(RedissonClient client, MydddConsistencyProperties properties) {
        if (client == null) {
            throw new IllegalArgumentException("RedissonClient 不能为 null：锁端口没有存储后端就无法保证互斥");
        }
        if (properties == null) {
            throw new IllegalArgumentException("MydddConsistencyProperties 不能为 null：锁键前缀是它的职责");
        }
        this.client = client;
        this.keyPrefix = properties.getLock().getKeyPrefix();
    }

    @Override
    public <T> T executeLocked(String key, Duration waitTime, Duration leaseTime, Supplier<T> action) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("锁键不能为空白：空白键会让所有“空白名字”共用一把锁，"
                    + "互斥范围从一个聚合扩大到整个应用");
        }
        if (action == null) {
            throw new IllegalArgumentException("受保护的动作不能为 null：没有动作的加锁只是一次无意义的往返");
        }
        if (waitTime == null || waitTime.isNegative()) {
            throw new IllegalArgumentException("waitTime 不能为 null 或负数（收到 " + waitTime
                    + "）：负数会被当成一个已经过期的等待预算，表现是不等就直接失败，"
                    + "而这和“配错了值”看起来一模一样。不等待请传 Duration.ZERO");
        }
        if (leaseTime == null || leaseTime.isNegative()) {
            throw new IllegalArgumentException("leaseTime 不能为 null 或负数（收到 " + leaseTime
                    + "）：0 是 watchdog 的哨兵值（R3），负数则两条语义都不成立。"
                    + "要自动续租请显式传 Duration.ZERO，要有界租约请传正数");
        }

        RLock lock = client.getLock(lockName(key));
        long waitMillis = waitTime.toMillis();
        boolean held;
        try {
            if (leaseTime.isZero()) {
                held = lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
            } else {
                held = lock.tryLock(waitMillis, leaseTime.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            // 恢复中断位再抛业务异常：吞掉中断会让上层线程池以为"没人叫我停"，
            // 而调用方此刻确实还没拿到锁，语义与超时失败一致。
            Thread.currentThread().interrupt();
            throw BizException.causedBy(CommonErrorCode.LOCK_ACQUIRE_FAILED, e);
        }
        if (!held) {
            throw new BizException(CommonErrorCode.LOCK_ACQUIRE_FAILED);
        }
        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 锁的 Redis 键名：{@code keyPrefix + ":" + key}。
     *
     * <p>前缀段是"哪些锁属于本应用"的唯一凭据（见 {@link MydddConsistencyProperties.Lock#getKeyPrefix()}）。
     * 这里<b>不做</b>任何规范化（不转大写、不把 {@code ::} 折叠成 {@code :}）：Redisson 的锁名就是要
     * {@code redis-cli} 里能一眼对上业务坐标的那个串，任何加工都会让"扫一眼锁"变成"先猜框架改了什么"。
     */
    public String lockName(String key) {
        return keyPrefix + ":" + key;
    }
}
