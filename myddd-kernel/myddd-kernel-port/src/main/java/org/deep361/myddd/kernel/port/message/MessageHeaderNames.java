package org.deep361.myddd.kernel.port.message;

/**
 * 框架保留的消息头名（跨厂商的线上契约，适配器与消费端共用）。
 *
 * <p>全部使用 {@code myddd-} 前缀，避免与厂商头（RocketMQ {@code KEYS}/{@code TAGS}、Kafka 自定义头）冲突。
 */
public final class MessageHeaderNames {

    /** 全链路 traceId，值与 {@code TraceContext.currentTraceId()} 一致。 */
    public static final String TRACE_ID = "myddd-trace-id";

    /** 领域事件 id（{@code DomainEvent#eventId()}），消费端幂等的首选依据。 */
    public static final String EVENT_ID = "myddd-event-id";

    /** 领域事件类型（{@code DomainEvent#eventType()}，即事件类二进制类名），供消费端反序列化定位类。 */
    public static final String EVENT_TYPE = "myddd-event-type";

    private MessageHeaderNames() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }
}
