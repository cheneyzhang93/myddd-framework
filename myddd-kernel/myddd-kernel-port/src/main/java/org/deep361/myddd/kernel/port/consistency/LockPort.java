package org.deep361.myddd.kernel.port.consistency;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 分布式锁端口：由适配器实现（Plan 3：consistency-starter，Redisson）。
 *
 * <p><b>模板方法语义</b>：锁的获取与释放由实现负责（{@code finally} 中释放），调用方只提供动作——
 * 刻意不暴露 {@code tryLock}/{@code unlock} 原语，避免业务代码漏解锁。获取不到锁时实现必须抛
 * {@code BizException(CommonErrorCode.LOCK_ACQUIRE_FAILED)}（HTTP 409）。
 *
 * <p><b>实现契约</b>：{@code key} 不可为空白、{@code leaseTime} 必须为正数（违反抛
 * {@link IllegalArgumentException}）；实现须保证同一线程可重入（Redisson 的
 * {@code RLock} 天然可重入）。
 */
public interface LockPort {

    /**
     * 在分布式锁保护下执行动作。
     *
     * @param key       锁键（业务语义明确，如 {@code order:1}）
     * @param waitTime  获取锁的最长等待时长；{@link Duration#ZERO} 表示不等待
     * @param leaseTime 持锁时长，到期自动释放（防死锁），必须为正
     * @param action    受保护的动作
     * @param <T>       返回值类型
     * @return 动作返回值
     */
    <T> T executeLocked(String key, Duration waitTime, Duration leaseTime, Supplier<T> action);

    /** 默认时序：不等待获取、持锁 30 秒。 */
    default <T> T executeLocked(String key, Supplier<T> action) {
        return executeLocked(key, Duration.ZERO, Duration.ofSeconds(30), action);
    }

    /** 无返回值便捷重载（默认时序同上）。 */
    default void runLocked(String key, Runnable action) {
        executeLocked(key, () -> {
            action.run();
            return null;
        });
    }
}
