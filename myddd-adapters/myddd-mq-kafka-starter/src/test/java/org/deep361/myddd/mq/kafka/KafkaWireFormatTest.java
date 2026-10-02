package org.deep361.myddd.mq.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.message.InboundMessage;
import org.deep361.myddd.kernel.port.message.OutboundMessage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 线上格式纯函数组（WF1–WF8）：只调 {@link KafkaWireFormat} 的四个静态方法，不碰模板也不碰工厂。
 *
 * <p>与 Task 35 的形状差异是对应的：那边映射组（MAP10）并进端口测试类，是因为映射里掺了
 * {@code MessageQueueSelector} 与 {@code TIMER_DELIVER_MS} 两类需要端口参与的行为；
 * Kafka 侧的映射没有这两样，所以拆成"纯函数一组 + 端口级映射一组（KM）"，两边各测各的、没有重复断言。
 */
class KafkaWireFormatTest {

    private static final String CN = "订单已支付 ¥100 ✅";

    private static OutboundMessage msg(String channel, String key, String payload, Map<String, String> headers) {
        return new OutboundMessage(channel, payload, key, headers, null);
    }

    @Test
    void should_map_channel_payload_and_key_onto_the_producer_record() {
        ProducerRecord<String, String> record =
                KafkaWireFormat.toProducerRecord(msg("order.events", "k-1", CN, Map.of("x-a", "1")));
        // 点号原样成 topic（实测 ProducerRecord 只拒 null，k5out.txt 的 OK dotted）。
        assertThat(record.topic()).isEqualTo("order.events");
        assertThat(record.key()).isEqualTo("k-1");
        // String 载荷恒等直达：模板与记录的泛型参数都是 <String,String>，没有字节往返。
        assertThat(record.value()).isEqualTo(CN);
        // 实测 k7out.txt 第 2 段：三参构造的 partition/timestamp 都是 null——分区与时刻都交给厂商。
        assertThat(record.partition()).isNull();
        assertThat(record.timestamp()).isNull();
    }

    @Test
    void should_encode_header_values_as_utf_8_bytes() {
        Header header = KafkaWireFormat.header("x-note", CN);
        assertThat(header.value()).hasSize(25);
        assertThat(new String(header.value(), StandardCharsets.UTF_8)).isEqualTo(CN);
        // 同一串在三套编码下给出与 UTF-8 互不相同（或明确记录）的字节数（实测 k7out.txt 第 1 段，
        // ✅=U+2705 是 3 字节不是 4）：编码被换成 ISO-8859-1/UTF-16 时这两个断言必死。
        assertThat(CN.getBytes(StandardCharsets.ISO_8859_1)).hasSize(12);
        assertThat(CN.getBytes(StandardCharsets.US_ASCII)).hasSize(12);
        assertThat(CN.getBytes(StandardCharsets.UTF_16)).hasSize(26);
        assertThat(header.value().length)
                .isNotEqualTo(CN.getBytes(StandardCharsets.ISO_8859_1).length)
                .isNotEqualTo(CN.getBytes(StandardCharsets.UTF_16).length);
    }

    @Test
    void should_keep_a_blank_key_verbatim_instead_of_normalizing_it() {
        // 与 Task 35 MAP4（空白键当作"没有"）方向相反，是刻意的跨厂商不对称：
        // ProducerRecord 里 null 键与空串键在厂商语义里是两回事（null=分区器自由分配，""=参与哈希），
        // 框架替它归一就是替厂商做一次分区决定。账进 Task 39 与 README，不进代码。
        assertThat(KafkaWireFormat.toProducerRecord(msg("order-events", "   ", "p", Map.of())).key())
                .isEqualTo("   ");
        assertThat(KafkaWireFormat.toProducerRecord(msg("order-events", "", "p", Map.of())).key()).isEmpty();
        // 实测 k5out.txt 末行：(topic,null,value) 与两参路线的 null-key 等价 true。
        ProducerRecord<String, String> viaNull = new ProducerRecord<>("order-events", null, "p");
        assertThat(viaNull.key()).isNull();
        assertThat(KafkaWireFormat.toProducerRecord(msg("order-events", null, "p", Map.of())).key()).isNull();
    }

    @Test
    void should_write_every_header_without_reordering_or_renaming() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Up", " a ");
        headers.put("订单号", "1");
        headers.put("myddd-trace-id", "t-1");
        OutboundMessage message = msg("order-events", null, "p", headers);
        ProducerRecord<String, String> record = KafkaWireFormat.toProducerRecord(message);
        Header[] written = record.headers().toArray();
        assertThat(written).hasSize(3);
        // 钉的是"映射不改序"：期望序取 message.headers() 自身的遍历序，而不是字面的插入序——内核
        // OutboundMessage 用 Map.copyOf 存头（一手实测：它的遍历序不等于插入序），所以"插入序"在框架这一层
        // 根本不是可承诺的事实，按写下顺序断言等于替内核撒了个它做不到的谎。本条能杀的是
        // toProducerRecord 里任何排序、倒序或换容器：那三种改法在此处与期望序都不相等。
        List<String> expectedOrder = new ArrayList<>();
        message.headers().forEach((name, value) -> expectedOrder.add(name));
        List<String> actualOrder = new ArrayList<>();
        for (Header header : written) {
            actualOrder.add(header.key());
        }
        assertThat(actualOrder).isEqualTo(expectedOrder);
        // 名字逐个逐字：大小写不改、不转小写、不 trim（实测 key 逐字 true）。按名字取而不按下标：
        // 下标读的是序，序已由上一条钉住；这里只钉"每一个名字都原样上了飞机、值没被改写"。
        assertThat(actualOrder).containsExactlyInAnyOrder("X-Up", "订单号", "myddd-trace-id");
        assertThat(valueOf(written, "X-Up")).isEqualTo(" a ");
        assertThat(valueOf(written, "订单号")).isEqualTo("1");
        assertThat(valueOf(written, "myddd-trace-id")).isEqualTo("t-1");
    }

    /** 按名字取那条头的值（读方向复用 {@link KafkaWireFormat#valueOf}，等于顺带钉住往返一致）。 */
    private static String valueOf(Header[] written, String name) {
        return KafkaWireFormat.valueOf(Arrays.stream(written)
                .filter(header -> header.key().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有名为 " + name + " 的头")));
    }

    @Test
    void should_fold_duplicate_header_names_onto_the_last_value() {
        RecordHeaders headers = new RecordHeaders();
        headers.add(KafkaWireFormat.header("x-a", "1"));
        headers.add(KafkaWireFormat.header("x-a", "2"));
        // 实测 k7out.txt 第 5 段：折叠结果={x-a=2}，lastHeader(x-a)=2。"取最后一条"与厂商 lastHeader 对齐，
        // 写成"取第一条"的变异在这里必死。
        assertThat(KafkaWireFormat.userHeaders(headers)).containsExactly(Map.entry("x-a", "2"));
        assertThat(KafkaWireFormat.valueOf(headers.lastHeader("x-a"))).isEqualTo("2");
    }

    @Test
    void should_drop_headers_whose_value_bytes_are_null() {
        // 读方向宽容：空值头是合法线上形态（实测 new RecordHeader(name,null) 的 value()==null
        // 且 key() 逐字保留，k7out.txt 第 4 段），Task 38 会真遇到，这里必须先摘掉而不是读出 null。
        RecordHeaders headers = new RecordHeaders();
        headers.add(KafkaWireFormat.header("x-a", "1"));
        headers.add(new RecordHeader("x-null", null));
        headers.add(KafkaWireFormat.header("x-b", "2"));
        assertThat(KafkaWireFormat.userHeaders(headers))
                .hasSize(2)
                .doesNotContainKey("x-null")
                .containsEntry("x-a", "1")
                .containsEntry("x-b", "2");
    }

    @Test
    void should_return_an_empty_mutable_map_for_a_null_or_empty_header_set() {
        Map<String, String> fromNull = KafkaWireFormat.userHeaders(null);
        fromNull.put("x-a", "1");
        Map<String, String> fromEmpty = KafkaWireFormat.userHeaders(new ProducerRecord<>("t", "v").headers());
        fromEmpty.put("x-a", "1");
        // 可 put ⇒ 是新的 LinkedHashMap，不是不可变视图（与 Task 36 WF6 同一手法；
        // Task 34 的 CM4 教训：调用方要原样携带顺序，这里不许换成 Map.copyOf）。
        assertThat(fromNull).containsExactly(Map.entry("x-a", "1"));
        assertThat(fromEmpty).containsExactly(Map.entry("x-a", "1"));
    }

    @Test
    void should_reject_null_arguments_at_every_write_side_entry() {
        // 写方向严格、读方向宽容（userHeaders(null) 的答案是空 map，WF7）：两套有意的规则，各钉各的。
        assertThatThrownBy(() -> KafkaWireFormat.toProducerRecord(null))
                .isInstanceOf(NullPointerException.class).hasMessage("message 不能为 null");
        assertThatThrownBy(() -> KafkaWireFormat.header(null, "v"))
                .isInstanceOf(NullPointerException.class).hasMessage("头名不能为 null");
        assertThatThrownBy(() -> KafkaWireFormat.header("x", null))
                .isInstanceOf(NullPointerException.class).hasMessage("头值不能为 null：x");
        assertThatThrownBy(() -> KafkaWireFormat.valueOf(null))
                .isInstanceOf(NullPointerException.class).hasMessage("header 不能为 null");
        assertThatCode(() -> KafkaWireFormat.userHeaders(null)).doesNotThrowAnyException();
    }

    // ==== 读方向（Task 38 的 WF9–WF14）：toInboundMessage(ConsumerRecord) ====

    private static ConsumerRecord<Object, Object> record(String topic, Object value, Headers headers) {
        return new ConsumerRecord<>(topic, 0, 77L, 1_700_000_000_000L, TimestampType.CREATE_TIME,
                ConsumerRecord.NULL_SIZE, ConsumerRecord.NULL_SIZE, null, value, headers, Optional.empty());
    }

    @Test
    void should_map_the_four_record_fields_onto_an_inbound_message() {
        RecordHeaders headers = new RecordHeaders();
        headers.add(KafkaWireFormat.header("x-a", "1"));
        InboundMessage message = KafkaWireFormat.toInboundMessage(record("a-channel", "{\"id\":1}", headers));
        // topic 逐字成 channel（无前后缀）。
        assertThat(message.channel()).isEqualTo("a-channel");
        assertThat(message.payload()).isEqualTo("{\"id\":1}");
        // messageId 是内核 javadoc 的连字符形态 topic-partition-offset（partition=0、offset=77 是夹具固定值）。
        assertThat(message.messageId()).isEqualTo("a-channel-0-77");
        assertThat(message.headers()).containsExactly(Map.entry("x-a", "1"));
    }

    @Test
    void should_pass_the_string_payload_through_without_a_second_decode() {
        ConsumerRecord<Object, Object> source = record("a-channel", "订单已支付", new RecordHeaders());
        InboundMessage message = KafkaWireFormat.toInboundMessage(source);
        // 载荷必须是同一个串：写成 String.valueOf 或 getBytes 往返都会在这里红（前者还会把 null 变成 "null"）。
        assertThat(message.payload()).isSameAs(source.value());
    }

    @Test
    void should_refuse_a_tombstone_record_naming_topic_partition_and_offset() {
        ConsumerRecord<Object, Object> tombstone = record("a-channel", null, new RecordHeaders());
        assertThatThrownBy(() -> KafkaWireFormat.toInboundMessage(tombstone))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("value=null")
                .hasMessageContaining("topic=a-channel")
                .hasMessageContaining("partition=0")
                .hasMessageContaining("offset=77")
                .hasMessageContaining("墓碑是日志压缩留下的删除标记");
    }

    @Test
    void should_refuse_a_non_string_payload_and_name_the_deserializer_key() {
        // Integer 载荷：这是"部署换错 value-deserializer"唯一的钉子。
        assertThatThrownBy(() -> KafkaWireFormat.toInboundMessage(record("a-channel", 42, new RecordHeaders())))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("java.lang.Integer")
                .hasMessageContaining("spring.kafka.consumer.value-deserializer")
                .hasMessageContaining("StringDeserializer");
        // Long 那一支证明消息用的是 getClass().getName()，不是硬编码类型名。
        assertThatThrownBy(() -> KafkaWireFormat.toInboundMessage(record("a-channel", 42L, new RecordHeaders())))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("java.lang.Long");
    }

    @Test
    void should_read_headers_with_the_same_single_rule_the_write_side_used() {
        RecordHeaders headers = new RecordHeaders();
        headers.add(KafkaWireFormat.header("x-a", "1"));
        headers.add(KafkaWireFormat.header("x-a", "2"));
        // 经由入站映射仍成立：同名头取最后一条（与 Task 37 的 WF5 同一份实现，没有另写一遍）。
        assertThat(KafkaWireFormat.toInboundMessage(record("a-channel", "p", headers)).headers())
                .containsExactly(Map.entry("x-a", "2"));
    }

    @Test
    void should_reject_a_null_record_and_accept_a_record_with_no_headers() {
        assertThatThrownBy(() -> KafkaWireFormat.toInboundMessage(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("record");
        // 五参构造给空 RecordHeaders（实测非 null）：正常返回、headers 为空。
        ConsumerRecord<String, String> noHeaders = new ConsumerRecord<>("a-channel", 0, 77L, "k", "v");
        InboundMessage message = KafkaWireFormat.toInboundMessage(noHeaders);
        assertThat(message.headers()).isEmpty();
    }
}
