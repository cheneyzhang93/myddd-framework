package org.deep361.myddd.testing.contract;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.trace.TraceContext;
import org.deep361.myddd.kernel.port.message.MessageHeaderNames;
import org.deep361.myddd.kernel.port.message.MessagePort;
import org.deep361.myddd.kernel.port.message.OutboundMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MessagePort} 的跨厂商契约：RocketMQ 与 Kafka 两个适配器必须给出**同一组**可观察结果。
 *
 * <p>子类只需要提供四个缝：{@link #messagePort()}（被测端口）、
 * {@link #sendAndReceive(OutboundMessage, Duration)}（把厂商消息映射回 {@link Delivered}）、
 * {@link #transportMessageCount()}（厂商侧真正落地的消息条数）、{@link #supportsDelayedDelivery()}（定点投递能力）。
 * surefire 用进程内录制器实现（无 Docker 也必须全绿，见 0.3-7），{@code *IT} 用真 broker 的消费者实现。
 *
 * <p>基类**只**断言端口层面的形状：不出现任何厂商类，也不假设 topic/queue 的命名规则。
 */
public abstract class MessagePortContractTestBase {

    private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 一次投递在厂商侧的可观察结果。
     *
     * @param channel 实际落到的通道
     * @param key     厂商侧的分区/顺序键，未给定时为 {@code null}
     * @param payload 消息体
     * @param headers 厂商侧的消息头（框架头必须原样在其中）
     */
    public record Delivered(String channel, String key, String payload, Map<String, String> headers) {

        public Delivered {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    /** 被测端口。 */
    protected abstract MessagePort messagePort();

    /** 发出并取回：IT 走真消费者，单测走录制器。取不到就抛 {@link AssertionError}。 */
    protected abstract Delivered sendAndReceive(OutboundMessage message, Duration timeout);

    /** 厂商侧已落地的消息总条数（用于"校验失败不该碰传输"这类否定用例）。 */
    protected abstract int transportMessageCount();

    /**
     * 该 provider 是否支持定点投递（R6）。Kafka 无原生延迟 ⇒ 返回 {@code false}，
     * 此时基类要求 {@code deliverAt} 一律**拒发**（{@link CommonErrorCode#ARGUMENT_INVALID}），
     * 而不是静默改成立即投递。
     */
    protected abstract boolean supportsDelayedDelivery();

    @AfterEach
    void clearTraceContext() {
        TraceContext.clear();
    }

    /** MQ-C1：最朴素的 send 必须原样到达，且只到达一次。 */
    @Test
    void should_round_trip_a_plain_payload() {
        Delivered delivered = sendAndReceive(OutboundMessage.of("order-events", "{\"a\":1}"), RECEIVE_TIMEOUT);
        assertEquals("order-events", delivered.channel());
        assertEquals("{\"a\":1}", delivered.payload());
        assertEquals(1, transportMessageCount());
    }

    /** MQ-C2：分区/顺序键必须真到厂商侧，不许在适配层被丢弃。 */
    @Test
    void should_carry_the_partition_key_verbatim() {
        Delivered delivered = sendAndReceive(
                new OutboundMessage("order-events", "{}", "order-42", Map.of(), null), RECEIVE_TIMEOUT);
        assertEquals("order-42", delivered.key());
    }

    /** MQ-C3：无键重载 ⇒ 厂商侧键必须是 null（而不是空串或通道名）。 */
    @Test
    void should_send_no_key_when_the_convenience_overload_is_used() {
        Delivered delivered = sendAndReceive(OutboundMessage.of("order-events", "{}"), RECEIVE_TIMEOUT);
        assertFalse(delivered.key() != null && !delivered.key().isBlank(),
                "无键消息在厂商侧出现了键：" + delivered.key());
    }

    /** MQ-C4：全部自定义头逐个原样到达（名字与值都不许改）。 */
    @Test
    void should_propagate_every_custom_header_unchanged() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-biz-line", "retail");
        headers.put("x-attempt", "3");
        Delivered delivered = sendAndReceive(
                new OutboundMessage("order-events", "{}", null, headers, null), RECEIVE_TIMEOUT);
        assertEquals("retail", delivered.headers().get("x-biz-line"));
        assertEquals("3", delivered.headers().get("x-attempt"));
    }

    /** MQ-C5：当前线程有 traceId 时，出站消息必须带 {@code myddd-trace-id} 头且值一致。 */
    @Test
    void should_write_the_trace_id_header_from_the_current_context() {
        TraceContext.set("trace-contract-5");
        Delivered delivered = sendAndReceive(OutboundMessage.of("order-events", "{}"), RECEIVE_TIMEOUT);
        assertEquals("trace-contract-5", delivered.headers().get(MessageHeaderNames.TRACE_ID));
    }

    /** MQ-C6：两条不同通道的消息不会串台。 */
    @Test
    void should_keep_two_channels_apart() {
        Delivered first = sendAndReceive(OutboundMessage.of("order-events", "1"), RECEIVE_TIMEOUT);
        Delivered second = sendAndReceive(OutboundMessage.of("payment-events", "2"), RECEIVE_TIMEOUT);
        assertEquals("order-events", first.channel());
        assertEquals("payment-events", second.channel());
        assertEquals(2, transportMessageCount());
    }

    /** MQ-C7：空白通道在**内核 record** 阶段就构造不出消息，因此一条都不许上运输（钉住"适配器只许走 {@link OutboundMessage} 这一个入口"）。 */
    @Test
    void should_reject_a_blank_channel_before_touching_the_transport() {
        assertThrows(IllegalArgumentException.class,
                () -> new OutboundMessage("  ", "{}", null, Map.of(), null));
        assertEquals(0, transportMessageCount());
    }

    /** MQ-C8/R6：定点投递要么真支持、要么明确拒发，绝不允许"看起来发了其实降级成立即投递"。 */
    @Test
    void should_honor_the_declared_delay_capability() {
        OutboundMessage delayed = new OutboundMessage("order-events", "{}", null, Map.of(),
                Instant.now().plus(Duration.ofMinutes(5)));
        if (supportsDelayedDelivery()) {
            messagePort().send(delayed);
            assertEquals(1, transportMessageCount());
        } else {
            BizException failure = assertThrows(BizException.class, () -> messagePort().send(delayed));
            assertEquals(CommonErrorCode.ARGUMENT_INVALID, failure.getErrorCode());
            assertTrue(failure.getMessage().contains("deliverAt"),
                    "拒发延迟消息时必须点出被拒的参数，实际：" + failure.getMessage());
            assertEquals(0, transportMessageCount());
        }
    }
}
