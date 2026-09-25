package org.deep361.myddd.domain.outbox;

import java.time.Duration;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * 投递失败后的退避策略：给定"已失败尝试次数"返回下次投递前要等的时长。
 *
 * <p><b>指数增长 + 只向下抖动</b>：{@code base = initial × multiplier^(failedAttempts-1)}，上限 {@code max}；
 * 再乘 {@code 1 - jitterRatio × random()}（{@code random ∈ [0,1)}）——抖动只会让重试更早，绝不会突破 {@code max}
 * 或让间隔为负。抖动的用途：大批记录在同一时刻失败时错开重试时刻，避免惊群。</p>
 *
 * <p><b>随机源可注入</b>：测试传固定取值即可让退避完全确定；投产使用 {@code ThreadLocalRandom}（见自动配置）。</p>
 *
 * <p><b>为什么退避是一个独立对象</b>：它是纯函数（失败次数 → 时长），不碰存储也不碰消息，因此可以单测穷尽边界；
 * 投递器只负责把它的结果写进 {@code nextRetryAt}。</p>
 */
public final class OutboxBackoff {

    private final Duration initial;
    private final double multiplier;
    private final Duration max;
    private final double jitterRatio;
    private final DoubleSupplier random;

    private OutboxBackoff(Duration initial, double multiplier, Duration max, double jitterRatio,
                          DoubleSupplier random) {
        this.initial = initial;
        this.multiplier = multiplier;
        this.max = max;
        this.jitterRatio = jitterRatio;
        this.random = random;
    }

    /**
     * 指数退避：第 {@code n} 次失败的基准时长为 {@code initial × multiplier^(n-1)}（按 {@code max} 封顶），
     * 再施加只向下的抖动。
     *
     * @param initial     首次失败后的等待时长（正时长）
     * @param multiplier  每次失败的放大倍数，必须 ≥ 1.0（等于 1.0 表示不放大）
     * @param max         等待时长上限，必须 ≥ {@code initial}
     * @param jitterRatio 抖动比例，取值区间 {@code [0.0, 1.0)}
     * @param random      随机源，返回 {@code [0,1)} 的 double，不可为 null
     */
    public static OutboxBackoff exponential(Duration initial, double multiplier, Duration max, double jitterRatio,
                                            DoubleSupplier random) {
        Objects.requireNonNull(initial, "initial 不能为 null");
        if (initial.isZero() || initial.isNegative()) {
            throw new IllegalArgumentException("initial 必须为正时长，实际 " + initial);
        }
        if (Double.isNaN(multiplier) || multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier 必须 ≥ 1.0，实际 " + multiplier);
        }
        Objects.requireNonNull(max, "max 不能为 null");
        if (max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("max 必须 ≥ initial（" + initial + "），实际 " + max);
        }
        if (Double.isNaN(jitterRatio) || jitterRatio < 0.0 || jitterRatio >= 1.0) {
            throw new IllegalArgumentException("jitterRatio 必须在 [0.0, 1.0) 区间，实际 " + jitterRatio);
        }
        Objects.requireNonNull(random, "random 不能为 null");
        return new OutboxBackoff(initial, multiplier, max, jitterRatio, random);
    }

    /** 固定间隔（不增长、不抖动）：用于"每次失败后等同样久"的简单策略与测试。 */
    public static OutboxBackoff fixed(Duration interval) {
        Objects.requireNonNull(interval, "interval 不能为 null");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval 必须为正时长，实际 " + interval);
        }
        return new OutboxBackoff(interval, 1.0, interval, 0.0, () -> 0.0);
    }

    /**
     * 第 {@code failedAttempts} 次失败后应等待的时长（结果恒为正时长）。
     *
     * @param failedAttempts 已失败尝试次数，从 1 开始
     */
    public Duration nextDelay(int failedAttempts) {
        if (failedAttempts < 1) {
            throw new IllegalArgumentException("failedAttempts 必须 ≥ 1，实际 " + failedAttempts);
        }
        double grownNanos = initial.toNanos() * Math.pow(multiplier, failedAttempts - 1);
        long baseNanos = Double.isFinite(grownNanos) && grownNanos < max.toNanos()
                ? (long) grownNanos
                : max.toNanos();
        double jitterFactor = 1.0 - jitterRatio * random.getAsDouble();
        long delayNanos = (long) (baseNanos * jitterFactor);
        return Duration.ofNanos(Math.max(delayNanos, 1L));
    }
}
