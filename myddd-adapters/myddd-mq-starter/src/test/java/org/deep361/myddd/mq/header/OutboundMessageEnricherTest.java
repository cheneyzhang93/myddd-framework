package org.deep361.myddd.mq.header;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.trace.TraceContext;
import org.deep361.myddd.kernel.port.message.MessageHeaderNames;
import org.deep361.myddd.kernel.port.message.OutboundMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * EN 组：出站头的单点整形。这里多写一个 traceId、少拒一个空白头，两个 provider 的等价性（Task 39）就会散架，
 * 所以断言的重点是"注入与否"与"拒绝与厂商无关"。
 */
class OutboundMessageEnricherTest {

    private final OutboundMessageEnricher enricher = new OutboundMessageEnricher();

    @AfterEach
    void resetTraceContext() {
        TraceContext.clear();
    }

    @Test
    void should_inject_the_current_trace_id() {
        TraceContext.set("trace-on-thread");

        OutboundMessage enriched = enricher.enrich(OutboundMessage.of("order-events", "{}"));

        assertThat(enriched.header(MessageHeaderNames.TRACE_ID)).contains("trace-on-thread");
    }

    @Test
    void should_keep_a_trace_id_the_caller_set() {
        TraceContext.set("trace-on-thread");
        OutboundMessage message = new OutboundMessage("order-events", "{}", null,
                Map.of(MessageHeaderNames.TRACE_ID, "explicit-trace"), null);

        // 显式优先于隐式，且无需改动就返回入参本身（零拷贝）
        assertThat(enricher.enrich(message)).isSameAs(message);
        assertThat(message.header(MessageHeaderNames.TRACE_ID)).contains("explicit-trace");
    }

    @Test
    void should_not_invent_a_trace_id_on_the_send_path() {
        OutboundMessage message = OutboundMessage.of("order-events", "{}");
        assertThat(TraceContext.currentTraceId()).isNull();

        // 发送侧不是入站边界：伪造会把"没有链路上下文"伪装成"有"，补上是消费侧（InboundDispatcher）的活
        assertThat(enricher.enrich(message)).isSameAs(message);
        assertThat(message.header(MessageHeaderNames.TRACE_ID)).isEmpty();
    }

    @Test
    void should_keep_the_event_headers_untouched() {
        TraceContext.set("trace-on-thread");
        OutboundMessage message = new OutboundMessage("order-events", "{}", "o-1", Map.of(
                MessageHeaderNames.EVENT_ID, "e-1",
                MessageHeaderNames.EVENT_TYPE, "com.example.OrderPaidEvent"), null);

        OutboundMessage enriched = enricher.enrich(message);

        // eventId/eventType 由 Outbox 投递器写（Task 58），整形层只追加 traceId，一个字段都不碰
        assertThat(enriched.header(MessageHeaderNames.EVENT_ID)).contains("e-1");
        assertThat(enriched.header(MessageHeaderNames.EVENT_TYPE)).contains("com.example.OrderPaidEvent");
        assertThat(enriched.header(MessageHeaderNames.TRACE_ID)).contains("trace-on-thread");
        assertThat(enriched.headers()).hasSize(3);
    }

    @Test
    void should_preserve_channel_payload_key_and_deliverAt() {
        TraceContext.set("trace-on-thread");
        Instant deliverAt = Instant.parse("2026-09-20T00:00:00Z");
        OutboundMessage message = new OutboundMessage("order-events", "{\"a\":1}", "key-1",
                Map.of("biz", "v"), deliverAt);

        OutboundMessage enriched = enricher.enrich(message);

        // R6 的"这个 provider 支不支持定点投递"由 provider 自己声明并拒发，整形层不替它做决定
        assertThat(enriched.channel()).isEqualTo(message.channel());
        assertThat(enriched.payload()).isEqualTo(message.payload());
        assertThat(enriched.key()).isEqualTo("key-1");
        assertThat(enriched.deliverAt()).isEqualTo(deliverAt);
        assertThat(enriched.header("biz")).contains("v");
    }

    @Test
    void should_not_mutate_the_input_message() {
        TraceContext.set("trace-on-thread");
        OutboundMessage message = OutboundMessage.of("order-events", "{}");

        OutboundMessage enriched = enricher.enrich(message);

        assertThat(enriched).isNotSameAs(message);
        assertThat(message.headers()).doesNotContainKey(MessageHeaderNames.TRACE_ID);
    }

    @Test
    void should_be_idempotent() {
        TraceContext.set("trace-on-thread");

        OutboundMessage once = enricher.enrich(OutboundMessage.of("order-events", "{}"));

        // 第二次落进"已有头就不覆盖"的分支：provider 重投路径上反复整形也不会长出第二个 traceId
        assertThat(enricher.enrich(once).headers()).isEqualTo(once.headers());
    }

    @Test
    void should_reject_a_null_message() {
        assertThatThrownBy(() -> enricher.enrich(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("message");
    }

    @Test
    void should_reject_blank_header_names_and_values() {
        // 一手实测：RocketMQ 5.5.1 的 putUserProperty 对空白名/空白值抛同一条
        // IllegalArgumentException("The name or value of property can not be null or blank string!")，
        // 而 Kafka 允许空字节头。不在壳里拒绝，Task 39 就要面对"同一消息一投一抛"的厂商差异。
        Throwable blankValue = catchThrowable(() -> enricher.enrich(
                new OutboundMessage("order-events", "{}", null, Map.of("biz", "  "), null)));
        assertThat(blankValue).isInstanceOf(BizException.class);
        assertThat(((BizException) blankValue).getErrorCode()).isEqualTo(CommonErrorCode.ARGUMENT_INVALID);
        assertThat(blankValue.getMessage()).contains("消息头 biz");

        Throwable blankName = catchThrowable(() -> enricher.enrich(
                new OutboundMessage("order-events", "{}", null, Map.of("  ", "v"), null)));
        assertThat(blankName).isInstanceOf(BizException.class);
        assertThat(((BizException) blankName).getErrorCode()).isEqualTo(CommonErrorCode.ARGUMENT_INVALID);
        assertThat(blankName.getMessage()).contains("空白头名");
    }

    @Test
    void should_skip_the_trace_header_when_the_switch_is_off() {
        TraceContext.set("trace-on-thread");
        OutboundMessageEnricher off = new OutboundMessageEnricher(false);

        OutboundMessage enriched = off.enrich(OutboundMessage.of("order-events", "{}"));

        // myddd.mq.trace-header.enabled=false 只做"不写 traceId"：空白头拒绝与其余整形照旧
        assertThat(enriched.header(MessageHeaderNames.TRACE_ID)).isEmpty();
        assertThatThrownBy(() -> off.enrich(
                new OutboundMessage("order-events", "{}", null, Map.of("biz", " "), null)))
                .isInstanceOf(BizException.class);
    }
}
