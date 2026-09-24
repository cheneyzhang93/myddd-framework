package org.deep361.myddd.kernel.ddd.event;

import java.time.Instant;

/**
 * 领域事件契约：进程内订阅与 Outbox 可靠投递共用。
 *
 * <p>信封字段 eventId / occurredAt / eventType 由事件对象自身携带；traceId、聚合类型与聚合 id
 * 由发布器在 Outbox 落库时补齐（领域装配模块，Plan 2 落地）。
 */
public interface DomainEvent {

    /** 事件唯一 id，<b>全局唯一</b>（跨事件类型亦不重复；消费端据此幂等与追踪）。 */
    String eventId();

    /** 事件发生时间。 */
    Instant occurredAt();

    /** 事件类型标识，默认取运行时类的<b>二进制类名</b>（{@code getClass().getName()}，嵌套类形如 {@code Outer$Inner}），供路由与反序列化定位。 */
    default String eventType() {
        return getClass().getName();
    }
}
