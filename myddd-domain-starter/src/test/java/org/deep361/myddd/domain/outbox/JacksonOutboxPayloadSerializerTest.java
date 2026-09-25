package org.deep361.myddd.domain.outbox;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JacksonOutboxPayloadSerializerTest {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final JacksonOutboxPayloadSerializer serializer = new JacksonOutboxPayloadSerializer();

    private final ObjectMapper mapper = JacksonOutboxPayloadSerializer.mapperBuilder().build();

    @Test
    void should_serialize_record_event_to_exact_json() {
        String json = serializer.serialize(OutboxPayloadFixtures.ORDER_PAID);

        assertThat(json).isEqualTo(OutboxPayloadFixtures.ORDER_PAID_JSON);
    }

    @Test
    void should_serialize_event_base_class_fields_and_skip_noise_methods() {
        Map<String, Object> json = mapper.readValue(
                serializer.serialize(new OutboxPayloadFixtures.PointsGrantedEvent("o-1", 30)), JSON_OBJECT);

        assertThat(json).containsOnlyKeys("orderId", "points", "eventId", "occurredAt");
        assertThat(json).containsEntry("orderId", "o-1").containsEntry("points", 30);
    }

    @Test
    void should_write_instant_as_iso_8601_text() {
        Map<String, Object> json = mapper.readValue(
                serializer.serialize(OutboxPayloadFixtures.ORDER_PAID), JSON_OBJECT);

        assertThat(json).containsEntry("occurredAt", "2026-09-25T05:00:00.123Z");
    }

    @Test
    void should_round_trip_record_event() {
        OutboxPayloadFixtures.OrderPaidEvent back = serializer.deserialize(
                OutboxPayloadFixtures.ORDER_PAID_JSON, OutboxPayloadFixtures.OrderPaidEvent.class);

        assertThat(back.eventId()).isEqualTo(OutboxPayloadFixtures.EVENT_ID);
        assertThat(back.occurredAt()).isEqualTo(OutboxPayloadFixtures.OCCURRED_AT);
        assertThat(back.orderId()).isEqualTo("o-1");
        assertThat(back.amount().currency()).isEqualTo("CNY");
        assertThat(back.amount().amount()).isEqualByComparingTo(new BigDecimal("9.90"));
        assertThat(back.settled()).isTrue();
        assertThat(back.status()).isEqualTo(OutboxPayloadFixtures.OrderStatus.PAID);
        assertThat(back.tags()).containsExactly("vip", "promo");
    }

    @Test
    void should_round_trip_class_event_and_reset_read_only_envelope() {
        OutboxPayloadFixtures.PointsGrantedEvent original = new OutboxPayloadFixtures.PointsGrantedEvent("o-2", 45);

        OutboxPayloadFixtures.PointsGrantedEvent back = serializer.deserialize(
                serializer.serialize(original), OutboxPayloadFixtures.PointsGrantedEvent.class);

        assertThat(back.orderId()).isEqualTo("o-2");
        assertThat(back.points()).isEqualTo(45);
        assertThat(back.eventId()).isNotEqualTo(original.eventId());
        // 禁止比较两次 now()：粗粒度时钟下同一时刻的 Instant 会相等；改为钉住"被植入的旧时间戳被忽略并重生成"
        OutboxPayloadFixtures.PointsGrantedEvent planted = serializer.deserialize(
                "{\"orderId\":\"o-2\",\"points\":45,\"occurredAt\":\"2000-01-01T00:00:00Z\"}",
                OutboxPayloadFixtures.PointsGrantedEvent.class);
        assertThat(planted.occurredAt()).isAfter(Instant.parse("2020-01-01T00:00:00Z"));
    }

    @Test
    void should_round_trip_event_declaring_envelope_in_constructor() {
        OutboxPayloadFixtures.ShippingConfirmedEvent original = new OutboxPayloadFixtures.ShippingConfirmedEvent(
                "e-7", OutboxPayloadFixtures.OCCURRED_AT, "o-3", 12);

        OutboxPayloadFixtures.ShippingConfirmedEvent back = serializer.deserialize(
                serializer.serialize(original), OutboxPayloadFixtures.ShippingConfirmedEvent.class);

        assertThat(back.eventId()).isEqualTo("e-7");
        assertThat(back.occurredAt()).isEqualTo(OutboxPayloadFixtures.OCCURRED_AT);
        assertThat(back.orderId()).isEqualTo("o-3");
        assertThat(back.points()).isEqualTo(12);
    }

    @Test
    void should_tolerate_unknown_properties_when_deserializing() {
        OutboxPayloadFixtures.PointsGrantedEvent back = serializer.deserialize(
                "{\"orderId\":\"o-4\",\"points\":30,\"futureField\":42}", OutboxPayloadFixtures.PointsGrantedEvent.class);

        assertThat(back.orderId()).isEqualTo("o-4");
        assertThat(back.points()).isEqualTo(30);
    }

    @Test
    void should_wrap_ambiguous_constructor_failure_in_framework_exception() {
        assertThatThrownBy(() -> serializer.deserialize(
                "{\"orderId\":\"o-5\",\"points\":1}", OutboxPayloadFixtures.AmbiguousEvent.class))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining(OutboxPayloadFixtures.AmbiguousEvent.class.getName())
                .hasCauseInstanceOf(JacksonException.class);
    }

    @Test
    void should_wrap_malformed_payload_failure_in_framework_exception() {
        assertThatThrownBy(() -> serializer.deserialize(
                "{\"eventId\":\"e-1\",", OutboxPayloadFixtures.OrderPaidEvent.class))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining(OutboxPayloadFixtures.OrderPaidEvent.class.getName())
                .hasCauseInstanceOf(JacksonException.class);
    }

    @Test
    void should_reject_payload_missing_primitive_property() {
        // 裁定：缺失原始类型属性不静默补默认值；"新增原始类型组件"是破坏性变更，只有"未知字段"方向被容忍
        assertThatThrownBy(() -> serializer.deserialize(
                "{\"orderId\":\"o-6\"}", OutboxPayloadFixtures.PointsGrantedEvent.class))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining(OutboxPayloadFixtures.PointsGrantedEvent.class.getName())
                .hasCauseInstanceOf(JacksonException.class);
    }

    @Test
    void should_wrap_serialization_failure_in_framework_exception() {
        assertThatThrownBy(() -> serializer.serialize(new OutboxPayloadFixtures.ExplodingAccessorEvent()))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining(OutboxPayloadFixtures.ExplodingAccessorEvent.class.getName())
                .hasCauseInstanceOf(JacksonException.class);
    }

    @Test
    void should_reject_null_arguments() {
        assertThatThrownBy(() -> serializer.serialize(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> serializer.deserialize(null, OutboxPayloadFixtures.OrderPaidEvent.class))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> serializer.deserialize("{}", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JacksonOutboxPayloadSerializer(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void should_expose_mapper_builder_with_framework_conventions() {
        JsonMapper custom = JacksonOutboxPayloadSerializer.mapperBuilder().build();

        assertThat(custom.writeValueAsString(OutboxPayloadFixtures.ORDER_PAID))
                .isEqualTo(OutboxPayloadFixtures.ORDER_PAID_JSON);
        assertThat(custom.readValue(OutboxPayloadFixtures.ORDER_PAID_JSON,
                OutboxPayloadFixtures.OrderPaidEvent.class).occurredAt())
                .isEqualTo(OutboxPayloadFixtures.OCCURRED_AT);
    }
}
