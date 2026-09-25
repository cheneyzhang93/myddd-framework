package org.deep361.myddd.domain.outbox;

import org.deep361.myddd.kernel.ddd.event.AbstractDomainEvent;
import org.deep361.myddd.kernel.ddd.event.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Outbox 序列化测试夹具：record 事件 / 继承 {@code AbstractDomainEvent} 的事件 / 自持信封的事件 /
 * 嵌套值对象 / 枚举 / 构造器歧义事件。
 *
 * <p>嵌套类型一律 {@code public}：Jackson 通过反射构造实例，非公开嵌套类型会引入可达性噪声，掩盖真实缺陷。
 */
public final class OutboxPayloadFixtures {

    private OutboxPayloadFixtures() {
    }

    public static final String EVENT_ID = "e-1";

    public static final Instant OCCURRED_AT = Instant.parse("2026-09-25T05:00:00.123Z");

    /** 线上格式基准：record 组件顺序即字段顺序（这是唯一断言"完整 JSON 文本"的用例）。 */
    public static final String ORDER_PAID_JSON = "{\"eventId\":\"e-1\",\"occurredAt\":\"2026-09-25T05:00:00.123Z\","
            + "\"orderId\":\"o-1\",\"amount\":{\"currency\":\"CNY\",\"amount\":9.90},\"settled\":true,"
            + "\"status\":\"PAID\",\"tags\":[\"vip\",\"promo\"]}";

    public enum OrderStatus {
        PAID, SHIPPED
    }

    /** 嵌套值对象（record 形态）。 */
    public record Money(String currency, BigDecimal amount) {
    }

    /** record 事件：信封字段是组件，可完整往返。 */
    public record OrderPaidEvent(String eventId, Instant occurredAt, String orderId, Money amount,
                                 boolean settled, OrderStatus status, List<String> tags) implements DomainEvent {
    }

    public static final OrderPaidEvent ORDER_PAID = new OrderPaidEvent(EVENT_ID, OCCURRED_AT, "o-1",
            new Money("CNY", new BigDecimal("9.90")), true, OrderStatus.PAID, List.of("vip", "promo"));

    /**
     * 继承 {@link AbstractDomainEvent} 的事件：信封字段由基类持有且只读，反序列化时被忽略——重建对象拿到
     * <b>新的</b> eventId / occurredAt（消费端幂等必须用消息头 {@code myddd-event-id}，见 Task 17）。
     *
     * <p>本类另混入 {@code toString()} / {@code staticNoise()} / {@code touch()} 三种"噪声方法"，用于钉住
     * 命名规则不会把它们当成属性。
     */
    public static final class PointsGrantedEvent extends AbstractDomainEvent {

        private final String orderId;
        private final int points;

        public PointsGrantedEvent(String orderId, int points) {
            this.orderId = orderId;
            this.points = points;
        }

        public String orderId() {
            return orderId;
        }

        public int points() {
            return points;
        }

        public static String staticNoise() {
            return "noise";
        }

        public void touch() {
        }

        @Override
        public String toString() {
            return "PointsGrantedEvent";
        }
    }

    /** 把信封放进构造器的事件：反序列化可完整还原信封（需要信封的消费场景用这种形态）。 */
    public static final class ShippingConfirmedEvent implements DomainEvent {

        private final String eventId;
        private final Instant occurredAt;
        private final String orderId;
        private final int points;

        public ShippingConfirmedEvent(String eventId, Instant occurredAt, String orderId, int points) {
            this.eventId = eventId;
            this.occurredAt = occurredAt;
            this.orderId = orderId;
            this.points = points;
        }

        @Override
        public String eventId() {
            return eventId;
        }

        @Override
        public Instant occurredAt() {
            return occurredAt;
        }

        public String orderId() {
            return orderId;
        }

        public int points() {
            return points;
        }
    }

    /** 构造器歧义事件：两个非默认构造器（2 参 + 1 参）——反序列化必失败。 */
    public static final class AmbiguousEvent implements DomainEvent {

        private final String orderId;
        private final int points;

        public AmbiguousEvent(String orderId, int points) {
            this.orderId = orderId;
            this.points = points;
        }

        public AmbiguousEvent(String orderId) {
            this(orderId, 0);
        }

        @Override
        public String eventId() {
            return orderId;
        }

        @Override
        public Instant occurredAt() {
            return Instant.EPOCH;
        }

        public String orderId() {
            return orderId;
        }

        public int points() {
            return points;
        }
    }
}
