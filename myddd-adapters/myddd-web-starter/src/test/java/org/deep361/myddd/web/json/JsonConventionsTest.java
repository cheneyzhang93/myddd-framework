package org.deep361.myddd.web.json;

import org.deep361.myddd.domain.outbox.JacksonOutboxPayloadSerializer;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 框架 JSON 约定的四条规则各自有用例，并额外断言与 Outbox payload 序列化逐字节一致。
 */
class JsonConventionsTest {

    private final ObjectMapper mapper = JsonConventions.frameworkMapper();

    private static FluentOrder fixture() {
        return new FluentOrder("ORD-1", new BigDecimal("88.50"),
                Instant.parse("2026-09-26T02:03:04Z"), "CNY");
    }

    @Test
    void should_serialize_fluent_accessors_instead_of_empty_object() {
        String written = mapper.writeValueAsString(new Money("12.30"));

        assertThat(written).contains("\"amount\":\"12.30\"").contains("\"currencyCode\":\"CNY\"");
        // 同一份输入交给没装 fluent 命名的默认 Mapper：只剩空对象，这就是本约定存在的理由
        assertThat(JsonMapper.builder().build().writeValueAsString(new Money("12.30"))).isEqualTo("{}");
    }

    @Test
    void should_expose_fluent_computed_properties_beyond_record_components() {
        assertThat(mapper.writeValueAsString(fixture())).contains("\"display\":\"ORD-1 CNY\"");
    }

    @Test
    void should_write_instant_as_iso8601_text_not_epoch_number() {
        assertThat(mapper.writeValueAsString(fixture()))
                .contains("\"occurredAt\":\"2026-09-26T02:03:04Z\"")
                .doesNotContain("\"occurredAt\":1");
    }

    @Test
    void should_tolerate_unknown_fields_for_rolling_deployments() {
        String jsonWithFutureField = "{\"orderId\":\"ORD-1\",\"amount\":\"88.50\","
                + "\"occurredAt\":\"2026-09-26T02:03:04Z\",\"currencyCode\":\"CNY\",\"futureField\":true}";

        assertThat(mapper.readValue(jsonWithFutureField, FluentOrder.class).orderId()).isEqualTo("ORD-1");
    }

    @Test
    void should_bind_single_constructor_by_property_names_with_parameters() {
        FluentOrder read = mapper.readValue("{\"orderId\":\"ORD-2\",\"amount\":\"1.50\","
                + "\"occurredAt\":\"2026-09-26T02:03:04Z\",\"currencyCode\":\"USD\"}", FluentOrder.class);

        assertThat(read.orderId()).isEqualTo("ORD-2");
        assertThat(read.amount()).isEqualByComparingTo("1.50");
        assertThat(read.occurredAt()).isEqualTo(Instant.parse("2026-09-26T02:03:04Z"));
        assertThat(read.currencyCode()).isEqualTo("USD");
    }

    @Test
    void should_reject_null_for_primitive_field_instead_of_zero() {
        assertThatThrownBy(() -> mapper.readValue("{\"quantity\":null}", Quantity.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void should_match_outbox_payload_bytes_field_for_field() {
        ProbeDomainEvent event = new ProbeDomainEvent("evt-1",
                Instant.parse("2026-09-26T02:03:04Z"), fixture());

        String viaWeb = mapper.writeValueAsString(event);
        String viaOutbox = new JacksonOutboxPayloadSerializer().serialize(event);

        assertThat(viaWeb).isEqualTo(viaOutbox)
                .contains("\"eventId\":\"evt-1\"")
                .contains("\"occurredAt\":\"2026-09-26T02:03:04Z\"")
                .contains("\"display\":\"ORD-1 CNY\"")
                // eventType() 是接口 default 方法，内核 fluent 策略把它排除在属性之外（两侧必须一致）
                .doesNotContain("eventType");
    }

    @Test
    void should_keep_standard_bean_naming_on_standard_rules() {
        // 内核 FluentAccessorNamingProvider 的 javadoc 规则：getURL() → URL、isDone() → done
        assertThat(mapper.writeValueAsString(new StandardBean()))
                .contains("\"URL\":\"http://example.com\"")
                .contains("\"done\":true");
    }

    /** fluent 值对象夹具（非 record，走 POJO 规则）。 */
    static final class Money {

        private final String amount;
        private final String currencyCode = "CNY";

        Money(String amount) {
            this.amount = amount;
        }

        public String amount() {
            return amount;
        }

        public String currencyCode() {
            return currencyCode;
        }
    }

    /** 原始类型字段：null 值必须失败，而不是静默变 0。 */
    static final class Quantity {

        private int quantity;

        public int getQuantity() {
            return quantity;
        }

        public void setQuantity(int quantity) {
            this.quantity = quantity;
        }
    }

    /** 标准 Bean 命名夹具：验证 fluent 兜底规则不改写已有标准形态。 */
    static final class StandardBean {

        public String getURL() {
            return "http://example.com";
        }

        public boolean isDone() {
            return true;
        }
    }
}
