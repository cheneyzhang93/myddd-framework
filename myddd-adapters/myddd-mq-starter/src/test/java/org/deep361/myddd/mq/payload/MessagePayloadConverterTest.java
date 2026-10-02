package org.deep361.myddd.mq.payload;

import org.deep361.myddd.domain.outbox.JacksonOutboxPayloadSerializer;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.ddd.event.DomainEvent;
import org.deep361.myddd.mq.listener.fixture.ProbeListener;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PC 组：载荷与线上格式的唯一出口。这一组真正的价值不在"能不能转 JSON"，而在
 * <b>MQ 侧读写的字节与 Outbox 侧写出的字节是同一套约定</b>（PC1），以及坏数据一律整体失败、绝不静默跳过。
 */
class MessagePayloadConverterTest {

    private final MessagePayloadConverter converter = new MessagePayloadConverter();

    private final JacksonOutboxPayloadSerializer outbox = new JacksonOutboxPayloadSerializer();

    @Test
    void should_produce_the_exact_same_json_as_the_outbox_serializer() {
        PaidEvent event = new PaidEvent("e-1", Instant.parse("2026-09-25T05:00:00Z"), "o-1", 3);

        String wire = converter.write(event);

        // 跨模块线上格式的唯一钉子：缺省 Mapper 取 domain 侧那一个来源，本模块不抄第二份约定
        assertThat(wire).isEqualTo(outbox.serialize(event));
        // Instant 写 ISO-8601 文本而非数字：这半条断言合起来才是"两个 provider 读同一份数据"的前提
        assertThat(wire).contains("\"occurredAt\":\"2026-09-25T05:00:00Z\"").contains("\"orderId\":\"o-1\"");
    }

    @Test
    void should_read_a_business_record_back() {
        ProbeListener.OrderPaid payload =
                new ProbeListener.OrderPaid("o-1", 3, Instant.parse("2026-09-20T00:00:00Z"));

        assertThat(converter.read(converter.write(payload), ProbeListener.OrderPaid.class)).isEqualTo(payload);
    }

    @Test
    void should_tolerate_unknown_fields() {
        ProbeListener.OrderPaid back = converter.read("{\"orderId\":\"o-1\",\"amount\":3,"
                + "\"paidAt\":\"2026-09-20T00:00:00Z\",\"extra\":1}", ProbeListener.OrderPaid.class);

        // 滚动升级方向：新生产者多写一个字段，旧消费者照常读完
        assertThat(back).isEqualTo(new ProbeListener.OrderPaid("o-1", 3, Instant.parse("2026-09-20T00:00:00Z")));
    }

    @Test
    void should_not_silently_default_a_null_primitive() {
        assertThatThrownBy(() -> converter.read("{\"orderId\":\"o-1\",\"amount\":null,"
                + "\"paidAt\":\"2026-09-20T00:00:00Z\"}", ProbeListener.OrderPaid.class))
                .isInstanceOf(FrameworkException.class);

        // FAIL_ON_NULL_FOR_PRIMITIVES 保持启用：缺失/置空原始类型不静默补 0，"新增原始类型组件"是破坏性变更
        assertThatThrownBy(() -> converter.read("{\"orderId\":\"o-1\"}", ProbeListener.OrderPaid.class))
                .isInstanceOf(FrameworkException.class);
    }

    @Test
    void should_name_the_target_type_when_reading_broken_json() {
        assertThatThrownBy(() -> converter.read("{\"a\":", ProbeListener.OrderPaid.class))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("MQ 载荷反序列化失败")
                .hasMessageContaining(ProbeListener.OrderPaid.class.getName());
    }

    @Test
    void should_reject_null_arguments() {
        assertThatThrownBy(() -> converter.write(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("payload");
        assertThatThrownBy(() -> converter.read(null, ProbeListener.OrderPaid.class))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("payload");
        assertThatThrownBy(() -> converter.read("{}", null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("type");
        assertThatThrownBy(() -> new MessagePayloadConverter(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("mapper");
    }

    /**
     * fluent 访问器的领域事件（PC1 的对照对象：Outbox 序列化只接受 {@link DomainEvent}）。
     *
     * <p>{@code public} 嵌套：Jackson 要反射构造它，非公开类型会引入可达性噪声、掩盖真实缺陷。
     */
    public static final class PaidEvent implements DomainEvent {

        private final String eventId;
        private final Instant occurredAt;
        private final String orderId;
        private final int amount;

        public PaidEvent(String eventId, Instant occurredAt, String orderId, int amount) {
            this.eventId = eventId;
            this.occurredAt = occurredAt;
            this.orderId = orderId;
            this.amount = amount;
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

        public int amount() {
            return amount;
        }
    }
}
