package org.deep361.myddd.domain.outbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxChannelResolverTest {

    private final OutboxChannelResolver resolver = OutboxChannelResolver.byAggregateType();

    @Test
    void should_derive_channel_from_qualified_class_name() {
        assertThat(resolver.resolve(OutboxPayloadFixtures.ORDER_PAID, "org.example.order.Order"))
                .isEqualTo("order-events");
        assertThat(resolver.resolve(OutboxPayloadFixtures.ORDER_PAID, "com.acme.billing.InvoiceRequest"))
                .isEqualTo("invoice-request-events");
    }

    @Test
    void should_use_simple_name_of_nested_aggregate() {
        assertThat(resolver.resolve(OutboxPayloadFixtures.ORDER_PAID, "org.example.order.Order$Line"))
                .isEqualTo("line-events");
    }

    @Test
    void should_handle_acronym_and_digit_boundaries() {
        assertThat(resolver.resolve(OutboxPayloadFixtures.ORDER_PAID, "com.acme.URLShortener"))
                .isEqualTo("urlshortener-events");
        assertThat(resolver.resolve(OutboxPayloadFixtures.ORDER_PAID, "com.acme.OAuth2Token"))
                .isEqualTo("oauth2-token-events");
        assertThat(resolver.resolve(OutboxPayloadFixtures.ORDER_PAID, "Order")).isEqualTo("order-events");
    }

    @Test
    void should_reject_blank_aggregate_type() {
        assertThatThrownBy(() -> resolver.resolve(OutboxPayloadFixtures.ORDER_PAID, null))
                .isInstanceOf(NullPointerException.class).hasMessage("aggregateType 不能为 null");
        assertThatThrownBy(() -> resolver.resolve(OutboxPayloadFixtures.ORDER_PAID, "  "))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("aggregateType 不能为空白");
        assertThatThrownBy(() -> resolver.resolve(OutboxPayloadFixtures.ORDER_PAID, "org.example."))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("aggregateType 缺少类简名：org.example.");
    }
}
