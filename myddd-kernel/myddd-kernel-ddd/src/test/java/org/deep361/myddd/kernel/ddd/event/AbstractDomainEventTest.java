package org.deep361.myddd.kernel.ddd.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbstractDomainEventTest {

    static class OrderPaidEvent extends AbstractDomainEvent {
        private final String orderId;

        OrderPaidEvent(String orderId) {
            this.orderId = orderId;
        }

        OrderPaidEvent(String orderId, String eventId, Instant occurredAt) {
            super(eventId, occurredAt);
            this.orderId = orderId;
        }

        String orderId() {
            return orderId;
        }
    }

    static class ShipmentCreatedEvent extends AbstractDomainEvent {
        private final String shipmentId;

        ShipmentCreatedEvent(String shipmentId) {
            this.shipmentId = shipmentId;
        }
    }

    @Test
    void should_generate_event_id_and_occurred_at() {
        Instant before = Instant.now();

        OrderPaidEvent event = new OrderPaidEvent("A1");

        Instant after = Instant.now();

        assertThat(event.eventId()).matches("[0-9a-fA-F-]{36}");
        assertThat(UUID.fromString(event.eventId()).toString()).isEqualTo(event.eventId());
        assertThat(event.occurredAt()).isBetween(before, after);
    }

    @Test
    void event_ids_should_be_unique() {
        Set<String> ids = IntStream.range(0, 100)
                .mapToObj(index -> new OrderPaidEvent("A" + index).eventId())
                .collect(Collectors.toSet());

        assertThat(ids).hasSize(100);
    }

    @Test
    void should_support_rehydration_constructor() {
        Instant at = Instant.parse("2026-09-20T10:00:00Z");

        OrderPaidEvent event = new OrderPaidEvent("A1", "evt-1", at);

        assertThat(event.orderId()).isEqualTo("A1");
        assertThat(event.eventId()).isEqualTo("evt-1");
        assertThat(event.occurredAt()).isEqualTo(at);
    }

    @Test
    void event_type_should_derive_from_runtime_class() {
        assertThat(new OrderPaidEvent("A1").eventType()).isEqualTo(OrderPaidEvent.class.getName());
        assertThat(new OrderPaidEvent("A1").eventType()).isNotEqualTo("OrderPaidEvent");
        assertThat(new ShipmentCreatedEvent("S1").eventType()).isEqualTo(ShipmentCreatedEvent.class.getName());
        assertThat(new ShipmentCreatedEvent("S1").eventType()).isNotEqualTo("ShipmentCreatedEvent");
        assertThat(new ShipmentCreatedEvent("S1").eventType()).isNotEqualTo(new OrderPaidEvent("A1").eventType());
    }

    @Test
    void should_implement_domain_event_contract() {
        assertThat(new OrderPaidEvent("A1")).isInstanceOf(DomainEvent.class);
    }

    @Test
    void should_generate_fresh_event_id_through_default_constructor() {
        assertThat(new OrderPaidEvent("A1").eventId()).isNotEqualTo("evt-1");
        assertThat(new OrderPaidEvent("A1").eventId()).isNotEqualTo("fixed-event-id");
        assertThat(new OrderPaidEvent("A1").eventId()).isNotEqualTo(new OrderPaidEvent("A2").eventId());
    }

    @Test
    void should_reject_null_envelope_fields() {
        assertThatThrownBy(() -> new OrderPaidEvent("A1", null, Instant.now()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("eventId 不能为 null");
        assertThatThrownBy(() -> new OrderPaidEvent("A1", "evt-1", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("occurredAt 不能为 null");
    }
}
