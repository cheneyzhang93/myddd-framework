package org.deep361.myddd.consistency.idempotent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.Clock;
import java.time.Duration;

/**
 * 测试用的可控时钟：{@code store=db} 那一支的到期判定完全由 {@link Clock} 说话，
 * 所以"占位已过期"这件事要靠<b>拨钟</b>演出来，而不是靠 {@code Thread.sleep}。
 *
 * <p>真 Redis 那一侧没有钟可拨（TTL 由服务端计时），所以等价用例基类把它抽象成
 * {@code letTimePassBeyond(Duration)}，Redis 子类睡、DB 子类拨钟——两条实现描述的是同一件事：
 * "从占位那一刻起，requested 时长已经过去"。
 */
final class MutableClock extends Clock {

    private Instant now;

    MutableClock(Instant start) {
        this.now = start;
    }

    void advance(Duration span) {
        this.now = this.now.plus(span);
    }

    /** 直接把时刻挪到某个绝对点，用于"到期前 1 毫秒/到期那一刻"这类边界。 */
    void set(Instant instant) {
        this.now = instant;
    }

    @Override
    public Instant instant() {
        return this.now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
