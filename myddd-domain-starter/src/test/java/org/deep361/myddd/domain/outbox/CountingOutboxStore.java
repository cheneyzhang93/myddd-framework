package org.deep361.myddd.domain.outbox;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 计数型 Outbox 存储替身（测试用）：只统计认领调用次数，供"轮询是否真的在跑"的断言使用。
 *
 * <p>可选让前 {@code failingClaims} 次认领抛 {@link RuntimeException}，用于验证单轮异常不杀死投递循环。
 */
public final class CountingOutboxStore implements OutboxStore {

    private final AtomicInteger claimCalls = new AtomicInteger();
    private final int failingClaims;

    public CountingOutboxStore() {
        this(0);
    }

    public CountingOutboxStore(int failingClaims) {
        this.failingClaims = failingClaims;
    }

    @Override
    public void save(OutboxRecord record) {
        throw new UnsupportedOperationException("计数型替身不保存记录");
    }

    @Override
    public List<OutboxRecord> claimDispatchable(Instant now, Instant leaseUntil, int limit) {
        if (claimCalls.incrementAndGet() <= failingClaims) {
            throw new RuntimeException("存储抖动");
        }
        return List.of();
    }

    @Override
    public void update(OutboxRecord record) {
        throw new UnsupportedOperationException("计数型替身不更新记录");
    }

    /** 已发生的认领调用次数。 */
    public int claimCalls() {
        return claimCalls.get();
    }
}
