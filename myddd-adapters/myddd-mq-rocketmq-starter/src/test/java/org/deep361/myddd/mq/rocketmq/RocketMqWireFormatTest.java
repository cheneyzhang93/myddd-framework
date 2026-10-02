package org.deep361.myddd.mq.rocketmq;

import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageExt;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.message.InboundMessage;
import org.deep361.myddd.mq.rocketmq.consume.RecordingRocketMqPushConsumer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入站方向的线上格式单点（WF1–WF10）：厂商 {@code MessageExt} → 内核 {@code InboundMessage}，
 * 以及"哪些属性名是厂商保留的"这一条事实的唯一来源。
 *
 * <p>三条边界都有一手实测撑住：新建 {@code MessageExt} 的 {@code getBody()} 与 {@code getProperties()}
 * 都是 {@code null}（WF4/WF6），属性表里可以出现 {@code null} 值（broker 解码路径写得出，WF7），
 * 而通道名的合法性只有一份规则——内核 record 与订阅期的 {@code Validators}（WF10）。
 */
class RocketMqWireFormatTest {

    private static final String TOPIC = "order-events";

    @Test
    void should_map_a_delivered_message_to_an_inbound_message() {
        MessageExt wire = RecordingRocketMqPushConsumer.wire("order-created", "{\"id\":1}", Map.of("x-a", "1"));

        InboundMessage message = RocketMqWireFormat.toInboundMessage(wire);

        // 通道名逐字等于厂商 topic：本框架不设 namespace，所以路由表的键两侧对得上（类文档那条）。
        assertThat(message.channel()).isEqualTo("order-created");
        assertThat(message.payload()).isEqualTo("{\"id\":1}");
        assertThat(message.messageId()).isEqualTo("msg-order-created");
        assertThat(message.headers()).containsExactly(Map.entry("x-a", "1"));
    }

    @Test
    void should_decode_the_body_as_utf_8_bytes() {
        String payload = "订单已支付 ¥100 ✅";
        MessageExt wire = RecordingRocketMqPushConsumer.wire(TOPIC, payload);
        // 与 Task 35 的出站方向同一串、同一个实测字节数：写与读若有一侧换了字符集，这里就断得出来。
        assertThat(wire.getBody()).hasSize(25);
        assertThat(RocketMqWireFormat.toInboundMessage(wire).payload()).isEqualTo(payload);
        // 换成 ISO-8859-1 会得到另一串文本（同一个字节数组的另一种读法），证明解码不是字符数透传。
        assertThat(new String(wire.getBody(), StandardCharsets.ISO_8859_1)).isNotEqualTo(payload);
    }

    @Test
    void should_tolerate_a_null_message_id_from_the_vendor() {
        MessageExt wire = RecordingRocketMqPushConsumer.wire(TOPIC, "{}", Map.of("x-a", "1"));
        wire.setMsgId(null);

        InboundMessage message = RocketMqWireFormat.toInboundMessage(wire);

        // 内核 javadoc：RocketMQ 为 msgId、Kafka 为 topic-partition-offset，可为 null。
        assertThat(message.messageId()).isNull();
        assertThat(message.channel()).isEqualTo(TOPIC);
        assertThat(message.payload()).isEqualTo("{}");
        assertThat(message.headers()).containsExactly(Map.entry("x-a", "1"));
    }

    @Test
    void should_fail_with_a_vendor_context_diagnosis_when_the_body_is_null() {
        // 实测：新建 MessageExt 的 getBody() 就是 null——"没载荷"是可达形态，不是不可能。
        MessageExt wire = RecordingRocketMqPushConsumer.wire(TOPIC, null);

        assertThatThrownBy(() -> RocketMqWireFormat.toInboundMessage(wire))
                .isInstanceOf(FrameworkException.class)
                .isNotInstanceOf(NullPointerException.class)
                .hasMessageContaining("topic=" + TOPIC)
                .hasMessageContaining("msgId=msg-" + TOPIC)
                .hasMessageContaining("reconsumeTimes=0")     // 实测新建消息的缺省值
                .hasMessageContaining("重投也修不好");
    }

    @Test
    void should_strip_vendor_system_properties_from_the_header_view() {
        MessageExt wire = RecordingRocketMqPushConsumer.wireWithSystemProperties(
                TOPIC, "{}", "k-1", "t-1", Map.of("x-a", "1"));
        // 前提先钉住：系统属性真在厂商表里，否则"摘除"是假的。
        assertThat(wire.getProperties()).containsKeys(MessageConst.PROPERTY_KEYS, MessageConst.PROPERTY_TAGS);

        assertThat(RocketMqWireFormat.userHeaders(wire)).containsExactly(Map.entry("x-a", "1"));
    }

    @Test
    void should_treat_a_null_properties_map_as_no_headers() {
        MessageExt wire = RecordingRocketMqPushConsumer.wire(TOPIC, "{}");
        assertThat(wire.getProperties()).isNull();       // 实测新建 MessageExt 就是 null

        Map<String, String> headers = RocketMqWireFormat.userHeaders(wire);
        assertThat(headers).isEmpty();
        // 返回值必须是一张新 map：往里写一次不抛，且厂商那张表仍是 null（不是活的内部 map）。
        headers.put("x-written", "1");
        assertThat(wire.getProperties()).isNull();

        assertThat(RocketMqWireFormat.toInboundMessage(wire).headers()).isEmpty();
    }

    @Test
    void should_drop_header_entries_whose_value_is_null() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("x-null", null);                          // 夹具用反射才写得出来（厂商公开 API 抛 IllegalArgumentException）
        raw.put("x-a", "1");
        MessageExt wire = RecordingRocketMqPushConsumer.wire(TOPIC, "{}", raw);

        assertThat(RocketMqWireFormat.userHeaders(wire)).containsExactly(Map.entry("x-a", "1"));
        // 内核 record 对 null 值 requireNonNull：不摘除就是投递期 NPE，这条断"不抛"。
        assertThatCode(() -> RocketMqWireFormat.toInboundMessage(wire)).doesNotThrowAnyException();
    }

    @Test
    void should_keep_header_names_and_values_verbatim() {
        MessageExt wire = RecordingRocketMqPushConsumer.wire(TOPIC, "{}",
                new LinkedHashMap<>(Map.of("X-Up", " a ", "订单号", "v-1")));

        Map<String, String> headers = RocketMqWireFormat.userHeaders(wire);

        // 实测厂商只拒保留名与空白值、不改写名字；框架也不替它 trim。
        assertThat(headers).containsOnlyKeys("X-Up", "订单号");
        assertThat(headers.get("X-Up")).isEqualTo(" a ");
        assertThat(headers.get("订单号")).isEqualTo("v-1");
    }

    @Test
    void should_answer_the_reserved_name_question_from_the_vendor_list() {
        for (String reserved : List.of(MessageConst.PROPERTY_KEYS, MessageConst.PROPERTY_TAGS, "UNIQ_KEY",
                MessageConst.PROPERTY_WAIT_STORE_MSG_OK, MessageConst.PROPERTY_DELAY_TIME_LEVEL, "TRACE_ON")) {
            assertThat(RocketMqWireFormat.isSystemProperty(reserved)).as(reserved).isTrue();
        }
        assertThat(RocketMqWireFormat.isSystemProperty("myddd-trace-id")).isFalse();
        assertThat(RocketMqWireFormat.isSystemProperty("x-a")).isFalse();
        assertThat(RocketMqWireFormat.isSystemProperty(null)).isFalse();     // 实测 contains(null) 不抛
        // 与 Task 35 的 MAP8 读同一个厂商事实：两处各自钉"写方向拒绝"与"读方向摘除"，漂移先在这里红。
        assertThat(MessageConst.STRING_HASH_SET).hasSize(54);
    }

    @Test
    void should_leave_the_channel_name_rules_to_the_kernel() {
        MessageExt wire = RecordingRocketMqPushConsumer.wire("  ", "{}");

        // 名字合法性只有一份规则（内核 record + 订阅期的 Validators），读方向不另立第三套。
        assertThatThrownBy(() -> RocketMqWireFormat.toInboundMessage(wire))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(FrameworkException.class)
                .hasMessageContaining("channel 不能为空白");
    }
}
