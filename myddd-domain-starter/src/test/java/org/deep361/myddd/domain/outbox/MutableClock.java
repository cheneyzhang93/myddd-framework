package org.deep361.myddd.domain.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/** 可推进的时钟（测试用）：退避/租约断言不依赖真实等待。 */
public final class MutableClock extends Clock {

    private final ZoneId zone;
    private Instant instant;

    public MutableClock(Instant instant) {
        this(instant, ZoneOffset.UTC);
    }

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = Objects.requireNonNull(instant, "instant 不能为 null");
        this.zone = Objects.requireNonNull(zone, "zone 不能为 null");
    }

    /** 向前推进指定时长。 */
    public void advance(Duration amount) {
        instant = instant.plus(Objects.requireNonNull(amount, "amount 不能为 null"));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
