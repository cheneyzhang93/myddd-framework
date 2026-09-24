package org.deep361.myddd.kernel.ddd.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 领域事件基类：默认生成 eventId（UUID）与 occurredAt（now）；另提供完整构造器供消息反序列化与补偿重放场景重建事件。
 *
 * <p><b>调用方契约</b>：子类只应承载业务字段——信封字段 eventId / occurredAt 由本类持有且不可变，
 * {@link DomainEvent#eventType()} 默认取运行时类的二进制名，子类一般无需覆写；确需稳定逻辑类型名
 * （例如类重命名后仍要兼容既有路由键）时可覆写。
 *
 * <p>本类刻意不实现 {@code Serializable}：事件的持久化与跨进程序列化由消息适配层（JSON）承担，
 * 不走 JDK 序列化，参见 {@code model/Entity.java} 的同类约定。
 */
public abstract class AbstractDomainEvent implements DomainEvent {

    private final String eventId;
    private final Instant occurredAt;

    /** 默认构造器：生成随机 eventId（UUID）并记录当前时刻为 occurredAt。 */
    protected AbstractDomainEvent() {
        this(UUID.randomUUID().toString(), Instant.now());
    }

    /**
     * 完整构造器：传入信封字段，供<b>反序列化</b>与<b>补偿重放</b>场景重建事件。
     *
     * <p>两个参数均<b>不可为 null</b>：{@code eventId} 为消费端幂等依据，{@code occurredAt} 参与
     * 事件时序判定，缺失即破坏事件语义，故此处直接拒绝（{@link NullPointerException}）。
     *
     * @param eventId 事件唯一 id，重建时必须沿用原始 id，不可重新生成
     * @param occurredAt 事件发生时间，重建时必须沿用原始时间，不可替换为当前时刻
     */
    protected AbstractDomainEvent(String eventId, Instant occurredAt) {
        this.eventId = Objects.requireNonNull(eventId, "eventId 不能为 null");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt 不能为 null");
    }

    @Override
    public String eventId() {
        return eventId;
    }

    @Override
    public Instant occurredAt() {
        return occurredAt;
    }
}
