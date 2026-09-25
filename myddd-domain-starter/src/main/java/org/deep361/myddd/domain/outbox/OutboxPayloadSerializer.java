package org.deep361.myddd.domain.outbox;

import org.deep361.myddd.kernel.ddd.event.DomainEvent;
import org.deep361.myddd.kernel.common.error.FrameworkException;

/**
 * Outbox 载荷序列化契约：领域事件 ⇄ JSON 文本。
 *
 * <p>本接口是领域装配层与持久化 / 消息适配器（Plan 3 落库、Plan 4 生产与消费）之间的稳定边界：记录器在业务
 * 事务内把事件序列化后写入 Outbox，投递器把同一段 JSON 作为消息体发出，消费端再用
 * {@link #deserialize(String, Class)} 重建事件——三段共享同一份格式约定，任何实现都必须与
 * {@code JacksonOutboxPayloadSerializer} 的线上格式兼容。
 *
 * <p><b>实现契约</b>：(a) 两个方法都不得返回 {@code null}；(b) 实现必须线程安全（投递器线程池会并发调用）；
 * (c) 失败一律抛 {@link FrameworkException}（包装底层异常），不得静默降级——序列化失败发生在业务事务内，
 * 抛出即触发事务回滚，避免"业务数据改了、事件没记下"；(d) 反序列化必须容忍未知字段（新生产者加字段、旧消费者
 * 仍可读），并且不得使用 payload 中的 {@code eventId} 作幂等依据（信封字段可能被重建，见接口 javadoc 之下的说明）。
 *
 * <p><b>信封语义</b>：payload 内的 {@code eventId} / {@code occurredAt} 仅供追溯与排障。继承
 * {@code AbstractDomainEvent} 的事件在反序列化时会重新生成这两个值（信封字段只读，无法回写）；消费端幂等
 * 一律以消息头 {@code myddd-event-id} 为准。
 */
public interface OutboxPayloadSerializer {

    /**
     * 序列化领域事件。
     *
     * @param event 领域事件，不可为 {@code null}
     * @return JSON 文本，非 {@code null}
     * @throws FrameworkException 序列化失败（事件类型不可序列化等）
     */
    String serialize(DomainEvent event);

    /**
     * 反序列化领域事件。
     *
     * @param payload   JSON 文本，不可为 {@code null}
     * @param eventType 目标事件类型，不可为 {@code null}
     * @return 重建的事件对象，非 {@code null}
     * @throws FrameworkException JSON 非法、类型不匹配或目标类型无可用构造器
     */
    <T extends DomainEvent> T deserialize(String payload, Class<T> eventType);
}
