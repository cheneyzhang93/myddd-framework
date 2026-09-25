package org.deep361.myddd.domain.outbox;

import java.time.Duration;
import java.util.Objects;

/**
 * 一轮投递的参数（由 {@code myddd.domain.outbox.*} 配置绑定）。
 *
 * @param maxAttempts   单条记录的最大投递尝试次数（含首次发送）：第 {@code retryCount + 1} 次失败的尝试
 *                      {@code ≥ maxAttempts} 时进死信
 * @param batchSize     单轮最多认领条数
 * @param leaseDuration 认领租约时长：投递器崩溃后租约过期，记录可被重新认领
 */
public record OutboxDispatchOptions(int maxAttempts, int batchSize, Duration leaseDuration) {

    public OutboxDispatchOptions {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 必须 ≥ 1，实际 " + maxAttempts);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize 必须 ≥ 1，实际 " + batchSize);
        }
        Objects.requireNonNull(leaseDuration, "leaseDuration 不能为 null");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration 必须为正时长，实际 " + leaseDuration);
        }
    }

    /** 默认参数：最多尝试 5 次、单轮 50 条、租约 30s（与 {@code MydddOutboxProperties} 的默认值一致）。 */
    public static OutboxDispatchOptions defaults() {
        return new OutboxDispatchOptions(5, 50, Duration.ofSeconds(30));
    }
}
