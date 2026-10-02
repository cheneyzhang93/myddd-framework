package org.deep361.myddd.web.json;

import org.deep361.myddd.kernel.ddd.event.AbstractDomainEvent;

import java.time.Instant;

/**
 * 最小领域事件夹具：信封字段由 {@link AbstractDomainEvent} 持有，业务字段只有一个 fluent 载荷。
 *
 * <p>它刻意不覆写 {@code eventType()}，因此本夹具同时检验"接口 default 方法不是属性"这条规则在
 * web 侧与 Outbox 侧同样成立。
 */
final class ProbeDomainEvent extends AbstractDomainEvent {

    private final FluentOrder order;

    ProbeDomainEvent(String eventId, Instant occurredAt, FluentOrder order) {
        super(eventId, occurredAt);
        this.order = order;
    }

    public FluentOrder order() {
        return order;
    }
}
