package org.deep361.myddd.mq.rocketmq.produce;

import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.exception.RemotingConnectException;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.common.trace.TraceContext;
import org.deep361.myddd.kernel.port.message.MessageHeaderNames;
import org.deep361.myddd.kernel.port.message.OutboundMessage;
import org.deep361.myddd.mq.MqProvider;
import org.deep361.myddd.mq.ProviderMessagePort;
import org.deep361.myddd.mq.header.OutboundMessageEnricher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 线上格式（MAP）、有序性兑现（SEL）、失败翻译（FL）与 SPI 声明（PP）四组，全部进程内、零 broker。
 */
class RocketMqMessagePortTest {

    private final RecordingRocketMqProducer producer = new RecordingRocketMqProducer();
    private final RocketMqMessagePort port = new RocketMqMessagePort(producer, new OutboundMessageEnricher());

    @AfterEach
    void clearTraceContext() {
        TraceContext.clear();
    }

    // ---- PP：ProviderMessagePort 的 SPI 面 ----

    @Test
    void should_report_the_rocketmq_provider() {
        assertThat(port.provider()).isEqualTo(MqProvider.ROCKETMQ);
    }

    @Test
    void should_declare_delayed_delivery_support() {
        // R6 的 RocketMQ 侧答案：能力声明与 5.x 定时消息（TIMER_DELIVER_MS）同源，不空口支持。
        assertThat(port).isInstanceOf(ProviderMessagePort.class);
        assertThat(port.supportsDelayedDelivery()).isTrue();
    }

    @Test
    void should_enrich_the_message_before_mapping_it() {
        // enrich（Task 34）与映射（本模块）互不吞：trace 头与 TIMER_DELIVER_MS 必须同时在场。
        TraceContext.set("pp3-trace");
        Instant deliverAt = Instant.now().plusSeconds(600);
        port.send(new OutboundMessage("order-events", "{}", null, Map.of(), deliverAt));
        Message wire = producer.lastWire();
        assertThat(wire.getUserProperty(MessageHeaderNames.TRACE_ID)).isEqualTo("pp3-trace");
        assertThat(wire.getProperty(MessageConst.PROPERTY_TIMER_DELIVER_MS))
                .isEqualTo(String.valueOf(deliverAt.toEpochMilli()));
    }

    @Test
    void should_reject_null_arguments() {
        assertThatThrownBy(() -> port.send(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("message");
        assertThatThrownBy(() -> new RocketMqMessagePort(null, new OutboundMessageEnricher()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("producer");
        assertThatThrownBy(() -> new RocketMqMessagePort(producer, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("enricher");
    }

    // ---- MAP：OutboundMessage → 厂商 Message 的逐字段映射 ----

    @Test
    void should_map_the_channel_to_the_topic_and_the_payload_to_the_body() {
        // 含点号通道名原样成 topic（§1.2.1 的括号形式配置与线上名字同源，框架不重命名）。
        port.send(OutboundMessage.of("order.events", "{\"id\":7}"));
        Message wire = producer.lastWire();
        assertThat(wire.getTopic()).isEqualTo("order.events");
        assertThat(new String(wire.getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":7}");
    }

    @Test
    void should_encode_the_payload_as_utf_8_bytes() {
        String payload = "订单已支付 ¥100 ✅";
        // 一手实测 25 字节；同一串按 ISO-8859-1 编码得到 12 字节（字符数），断言二者不同即钉住 UTF-8。
        assertThat(payload.getBytes(StandardCharsets.UTF_8)).hasSize(25);
        assertThat(payload.getBytes(StandardCharsets.ISO_8859_1)).hasSize(12);
        port.send(OutboundMessage.of("order-events", payload));
        Message wire = producer.lastWire();
        assertThat(wire.getBody()).hasSize(25);
        assertThat(new String(wire.getBody(), StandardCharsets.UTF_8)).isEqualTo(payload);
    }

    @Test
    void should_not_write_any_key_property_when_the_key_is_absent() {
        // 实测 setKeys(null) 会写入 KEYS=null，所以"无键"只能是"不调用"。
        port.send(OutboundMessage.of("order-events", "{}"));
        Message wire = producer.lastWire();
        assertThat(wire.getProperties()).doesNotContainKey(MessageConst.PROPERTY_KEYS);
        assertThat(wire.getKeys()).isNull();
    }

    @Test
    void should_treat_a_blank_key_as_absent() {
        port.send(new OutboundMessage("order-events", "{}", "   ", Map.of(), null));
        RecordingRocketMqProducer.Sent sent = producer.sent().get(0);
        assertThat(sent.message().getProperties()).doesNotContainKey(MessageConst.PROPERTY_KEYS);
        assertThat(sent.selector()).isNull();
        assertThat(sent.argument()).isNull();
    }

    @Test
    void should_write_the_key_verbatim_into_the_keys_property() {
        port.send(OutboundMessage.of("order-events", "{}", "order-42"));
        Message wire = producer.lastWire();
        assertThat(wire.getKeys()).isEqualTo("order-42");
        assertThat(wire.getProperty(MessageConst.PROPERTY_KEYS)).isEqualTo("order-42");

        // 实测厂商不 trim，框架也不替它 trim：带空格的 key 原样上飞机。
        port.send(OutboundMessage.of("order-events", "{}", " a "));
        assertThat(producer.lastWire().getProperty(MessageConst.PROPERTY_KEYS)).isEqualTo(" a ");
    }

    @Test
    void should_write_every_custom_header_as_a_user_property() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-biz-line", "retail");
        headers.put("x-attempt", "3");
        port.send(new OutboundMessage("order-events", "{}", null, headers, null));
        Message wire = producer.lastWire();
        assertThat(wire.getUserProperty("x-biz-line")).isEqualTo("retail");
        assertThat(wire.getUserProperty("x-attempt")).isEqualTo("3");
    }

    @Test
    void should_reject_a_reserved_property_name_before_touching_the_producer() {
        // 六个保留名逐个钉：拒绝发生在映射期，请求根本没上飞机（录制器计数 0）。
        for (String reserved : List.of(MessageConst.PROPERTY_KEYS, MessageConst.PROPERTY_TAGS,
                "UNIQ_KEY", MessageConst.PROPERTY_WAIT_STORE_MSG_OK,
                MessageConst.PROPERTY_DELAY_TIME_LEVEL, "TRACE_ON")) {
            RecordingRocketMqProducer fresh = new RecordingRocketMqProducer();
            RocketMqMessagePort freshPort = new RocketMqMessagePort(fresh, new OutboundMessageEnricher());
            assertThatThrownBy(() -> freshPort.send(
                    new OutboundMessage("order-events", "{}", null, Map.of(reserved, "v"), null)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining(reserved)
                    .extracting("errorCode")
                    .isEqualTo(CommonErrorCode.ARGUMENT_INVALID);
            assertThat(fresh.sent()).isEmpty();
        }
    }

    @Test
    void should_keep_the_framework_header_names_writable() {
        // 拒绝与过滤都以厂商名单为准（不抄名单），但名单不许吃掉 myddd-* 头——三个框架头逐个能写出。
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(MessageHeaderNames.TRACE_ID, "t1");
        headers.put(MessageHeaderNames.EVENT_ID, "e1");
        headers.put(MessageHeaderNames.EVENT_TYPE, "com.example.OrderPaid");
        port.send(new OutboundMessage("order-events", "{}", null, headers, null));
        Message wire = producer.lastWire();
        assertThat(wire.getUserProperty(MessageHeaderNames.TRACE_ID)).isEqualTo("t1");
        assertThat(wire.getUserProperty(MessageHeaderNames.EVENT_ID)).isEqualTo("e1");
        assertThat(wire.getUserProperty(MessageHeaderNames.EVENT_TYPE)).isEqualTo("com.example.OrderPaid");
        // 实测钉值：名单规模 54 项、myddd-* 不在其中（厂商版本漂移会先在这里红）。
        assertThat(MessageConst.STRING_HASH_SET).hasSize(54);
        assertThat(MessageConst.STRING_HASH_SET).doesNotContain("myddd-trace-id");
    }

    @Test
    void should_map_deliver_at_to_the_timer_property_in_epoch_millis() {
        Instant deliverAt = Instant.parse("2026-10-01T12:00:00Z");
        port.send(new OutboundMessage("order-events", "{}", null, Map.of(), deliverAt));
        Message wire = producer.lastWire();
        assertThat(wire.getProperty(MessageConst.PROPERTY_TIMER_DELIVER_MS))
                .isEqualTo(String.valueOf(deliverAt.toEpochMilli()));
        assertThat(wire.getDeliverTimeMs()).isEqualTo(deliverAt.toEpochMilli());
        // 实测两条互不干涉：5.x 定时不写经典 18 级延迟属性。
        assertThat(wire.getProperty(MessageConst.PROPERTY_DELAY_TIME_LEVEL)).isNull();
    }

    @Test
    void should_leave_the_message_untouched_when_deliver_at_is_absent() {
        port.send(OutboundMessage.of("order-events", "{}"));
        // 实测新消息缺省属性表就是 {WAIT=true}，任何 TIMER_* 都不许出现。
        assertThat(producer.lastWire().getProperties()).containsOnly(
                Map.entry(MessageConst.PROPERTY_WAIT_STORE_MSG_OK, "true"));
        assertThat(producer.lastWire().getProperties().keySet()).noneMatch(name -> name.startsWith("TIMER_"));

        port.send(OutboundMessage.of("order-events", "{}", "order-42"));
        assertThat(producer.lastWire().getProperties()).containsOnly(
                Map.entry(MessageConst.PROPERTY_KEYS, "order-42"),
                Map.entry(MessageConst.PROPERTY_WAIT_STORE_MSG_OK, "true"));
    }

    // ---- SEL：兑现内核 javadoc 的"同 key 同队列"承诺 ----

    @Test
    void should_use_the_queue_selector_only_when_a_key_is_present() {
        port.send(OutboundMessage.of("order-events", "1"));
        port.send(OutboundMessage.of("order-events", "2", "order-42"));
        assertThat(producer.sent().get(0).selector()).isNull();
        assertThat(producer.sent().get(1).selector()).isNotNull();
    }

    @Test
    void should_send_the_same_key_to_the_same_queue() {
        port.send(OutboundMessage.of("order-events", "{}", "order-42"));
        MessageQueueSelector selector = producer.sent().get(0).selector();
        List<MessageQueue> queues = List.of(
                new MessageQueue("order-events", "broker-a", 0),
                new MessageQueue("order-events", "broker-a", 1),
                new MessageQueue("order-events", "broker-a", 2),
                new MessageQueue("order-events", "broker-a", 3));
        // 相同参数 ⇒ 同一队列对象：内核"同 key 同队列有序"承诺的兑现点。
        assertThat(selector.select(queues, producer.lastWire(), "order-42"))
                .isSameAs(selector.select(queues, producer.lastWire(), "order-42"));
    }

    @Test
    void should_stay_inside_the_queue_range_for_the_worst_hash_code() {
        // 本用例的存在理由：这个串的 hashCode 恰为 Integer.MIN_VALUE，Math.abs 对它是负数。
        assertThat("polygenelubricants".hashCode()).isEqualTo(Integer.MIN_VALUE);
        port.send(OutboundMessage.of("order-events", "{}", "polygenelubricants"));
        MessageQueueSelector selector = producer.sent().get(0).selector();
        List<MessageQueue> queues = List.of(
                new MessageQueue("order-events", "broker-a", 0),
                new MessageQueue("order-events", "broker-a", 1),
                new MessageQueue("order-events", "broker-a", 2),
                new MessageQueue("order-events", "broker-a", 3));
        MessageQueue selected = selector.select(queues, producer.lastWire(), "polygenelubricants");
        assertThat(selected.getQueueId()).isBetween(0, 3);
    }

    @Test
    void should_pass_the_key_itself_as_the_selector_argument() {
        port.send(OutboundMessage.of("order-events", "payload", "order-42"));
        Object argument = producer.sent().get(0).argument();
        assertThat(argument).isInstanceOf(String.class).isEqualTo("order-42");
    }

    // ---- FL：把厂商的失败说成一句能行动的话 ----

    @Test
    void should_fail_when_the_broker_reports_a_non_ok_status() {
        for (SendStatus status : List.of(SendStatus.FLUSH_DISK_TIMEOUT,
                SendStatus.FLUSH_SLAVE_TIMEOUT, SendStatus.SLAVE_NOT_AVAILABLE)) {
            RecordingRocketMqProducer fresh = new RecordingRocketMqProducer();
            fresh.replyWith(RecordingRocketMqProducer.result(status));
            RocketMqMessagePort freshPort = new RocketMqMessagePort(fresh, new OutboundMessageEnricher());
            assertThatThrownBy(() -> freshPort.send(OutboundMessage.of("order-events", "{}")))
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining(status.name())
                    .hasMessageContaining("msg-" + status.name())
                    .hasMessageContaining("topic=order-events")
                    .hasMessageContaining("由 Outbox 重投");
            // 请求上过飞机，翻译发生在回来后：计数 1 而不是 0。
            assertThat(fresh.sent()).hasSize(1);
        }
    }

    @Test
    void should_accept_the_send_only_when_the_status_is_send_ok() {
        // FL1 的对照组：缺它则"任何状态都抛"也能全绿。
        assertThatCode(() -> port.send(OutboundMessage.of("order-events", "{}")))
                .doesNotThrowAnyException();
        assertThat(producer.sent()).hasSize(1);
    }

    @Test
    void should_translate_a_client_exception_and_keep_the_cause() {
        MQClientException client = new MQClientException(17, "No route info of this topic");
        producer.failNextWith(client);
        assertThatThrownBy(() -> port.send(OutboundMessage.of("order-events", "{}")))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("code=17")
                .hasMessageContaining("No route info of this topic")
                .hasMessageContaining("topic=order-events")
                .hasCauseReference(client);
    }

    @Test
    void should_translate_a_broker_exception_naming_the_response_code() {
        MQBrokerException broker = new MQBrokerException(2, "system busy");
        producer.failNextWith(broker);
        assertThatThrownBy(() -> port.send(OutboundMessage.of("order-events", "{}")))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("code=2")
                .hasMessageContaining("system busy")
                .hasCauseReference(broker);
    }

    @Test
    void should_translate_a_remoting_exception_including_its_subclasses() {
        RemotingConnectException connect = new RemotingConnectException("connect to 127.0.0.1:19876 failed");
        producer.failNextWith(connect);
        assertThatThrownBy(() -> port.send(OutboundMessage.of("order-events", "{}")))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("RemotingConnectException")
                .hasMessageContaining("connect to 127.0.0.1:19876 failed")
                .hasCauseReference(connect);
    }

    @Test
    void should_restore_the_interrupt_flag_when_the_send_was_interrupted() {
        producer.failNextWith(new InterruptedException("test"));
        try {
            assertThatThrownBy(() -> port.send(OutboundMessage.of("order-events", "{}")))
                    .isInstanceOf(FrameworkException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // 必须清标志：中断位泄漏会把同 JVM 里后面的用例一起毒掉。
            Thread.interrupted();
        }
    }

    @Test
    void should_wrap_an_undeclared_runtime_exception_from_the_vendor() {
        // 一手实测：name-server 不可达时厂商从 send() 抛出签名里没有的 IllegalStateException，
        // 消息不含 topic 不含 group——不兜住它，用户拿到的就是一条无上下文的 JDK 运行时异常。
        IllegalStateException vendor = new IllegalStateException("connect to 127.0.0.1:19876 failed");
        producer.failNextWith(vendor);
        assertThatThrownBy(() -> port.send(OutboundMessage.of("order-events", "{}")))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("java.lang.IllegalStateException")
                .hasMessageContaining("topic=order-events")
                .hasCauseReference(vendor);
    }
}
