package org.deep361.myddd.mq.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.message.InboundMessage;
import org.deep361.myddd.kernel.port.message.OutboundMessage;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Kafka 线上格式的单点：框架消息 ↔ 厂商 {@code ProducerRecord}/{@code Header}。
 *
 * <p><b>为什么只有这一个类碰字节与编码</b>：出站要"头名逐字保留、头值按 UTF-8 变字节"，
 * 入站（Task 38）要把字节读回字符串、同名头取最后一条。两条规则的方向相反但依据相同，
 * 分在两个包里就会出现"写用 UTF-8、读用缺省字符集"这种只能在生产环境发现的错。
 *
 * <p><b>撤回一处旧写法</b>：本计划 §0.1 曾写"Kafka 头值要 {@code new RecordHeader(k, Bytes.toUTF8(v))}"。
 * 本机 {@code kafka-clients-4.2.1} 实测 {@code org.apache.kafka.common.utils.Bytes} 的公开方法只有
 * {@code wrap/get/increment/compareTo/equals/hashCode/toString}，<b>没有</b> {@code toUTF8}
 * （取证：{@code D:/tmp/p4probe/kprobe/k1out.txt} 第 2–3 行）。正确写法是显式
 * {@code value.getBytes(StandardCharsets.UTF_8)}——多打一次 {@code StandardCharsets} 换掉一个不存在的 API，
 * 而且显式字符集本来就是"不依赖 JVM 缺省"的既有规矩（Task 35 的 MAP2 钉过同一件事）。
 *
 * <p><b>键原样透传，空白不归一</b>：这是与 RocketMQ 侧的一处<b>刻意</b>差异。RocketMQ 的
 * {@code setKeys(null)} 会真的写入 {@code KEYS=null}（实测），所以那侧必须"空白当作没有"；
 * Kafka 的 {@code ProducerRecord(topic, null, v)} 与 {@code (topic, "", v)} 在厂商语义里是两回事
 * （前者"无键、由分区器自由分配"，后者"键存在且为空串、参与哈希"），框架一旦替它归一，
 * 就是在改厂商的分区决定。这条不对称进 Task 39 的等价性清单，不进代码。
 *
 * <p><b>topic 形态不在这里校验</b>：实测 {@code ProducerRecord} 只拒绝 {@code null}
 * （{@code IllegalArgumentException: Topic cannot be null.}），空串、空白、含点号、中文、1000 字符全放行
 * （{@code k5out.txt}）。框架不自造第二套名单（Task 36 的 DC4 同一条纪律），形态问题留给厂商在
 * 取路由时报错，由 {@code KafkaMessagePort} 翻译成人话。
 *
 * <p>纯函数、无可变状态，线程安全。
 */
public final class KafkaWireFormat {

    private KafkaWireFormat() {
    }

    /**
     * 框架出站消息 → 厂商 {@link ProducerRecord}。
     *
     * <p>字段去向四条：{@code channel} → topic（逐字，含点号也原样）；{@code payload} → UTF-8 字节；
     * {@code key} → 分区键（逐字，空白保留）；{@code headers} → 逐个 {@link Header}。
     * {@code deliverAt} <b>不</b>出现在这里——Kafka 没有定点投递，端口在调用本方法之前就拒了（DD 组）。
     *
     * @param message 已富化的出站消息，不可为 {@code null}
     * @return 厂商记录，{@code partition} 与 {@code timestamp} 为 {@code null}（交给厂商决定）
     */
    public static ProducerRecord<String, String> toProducerRecord(OutboundMessage message) {
        Objects.requireNonNull(message, "message 不能为 null");
        ProducerRecord<String, String> record =
                new ProducerRecord<>(message.channel(), message.key(), message.payload());
        message.headers().forEach((name, value) -> record.headers().add(header(name, value)));
        return record;
    }

    /**
     * 头名 + 字符串值 → 厂商头。
     *
     * <p>值按 UTF-8 编码；{@code null} 值在框架侧不可能出现（内核 {@code OutboundMessage} 逐条
     * {@code requireNonNull}），厂商侧却允许（实测 {@code add(String, byte[])} 接受 {@code null} 数组，
     * {@code k1out.txt} 第 7 行），所以读方向必须容忍（{@link #valueOf(Header)}）。
     */
    public static Header header(String name, String value) {
        Objects.requireNonNull(name, "头名不能为 null");
        Objects.requireNonNull(value, "头值不能为 null：" + name);
        return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
    }

    /** 厂商头 → 字符串值；{@code null} 字节数组返回 {@code null} 而不是抛（空值头是合法线上形态）。 */
    public static String valueOf(Header header) {
        Objects.requireNonNull(header, "header 不能为 null");
        byte[] value = header.value();
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    /**
     * 厂商头集合 → 用户可见的 {@code Map}（读方向，Task 38 复用）。
     *
     * <p><b>同名头取最后一条</b>：实测 Kafka 的头是"可重复、迭代按插入序、{@code lastHeader} 返回最后加入的"
     * （{@code k1out.txt} 第 7–10 行：两次 {@code add("traceId", …)} 之后 {@code lastHeader} 给出第二次，
     * {@code headers("traceId")} 计数 2）。取最后一条与 {@code lastHeader} 一致，是与厂商对齐的选择，
     * 不是随手的 {@code put} 覆盖。
     *
     * <p>Kafka 没有"系统属性名单"这个东西（RocketMQ 的 {@code MessageConst.STRING_HASH_SET} 才需要摘除），
     * 所以本方法不做名字过滤——凡是发出来的头都是用户头。
     *
     * @return 新的 {@code LinkedHashMap}（迭代序=厂商头序）；入参为 {@code null} 时给空 map
     */
    public static Map<String, String> userHeaders(Headers headers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers == null) {
            return result;
        }
        for (Header header : headers) {
            String value = valueOf(header);
            if (value != null) {
                result.put(header.key(), value);
            }
        }
        return result;
    }

    /**
     * 厂商入站记录 → 内核 {@link InboundMessage}。
     *
     * <p><b>四个字段各自从哪来，只有一条翻译</b>：{@code topic} → {@code channel}（逐字，Kafka 的
     * topic 与框架的通道名本来就是同一个串——出站 {@link #toProducerRecord(OutboundMessage)} 写的就是它）；
     * {@code value} → {@code payload}（不再解码、不再转换，见下）；{@code topic-partition-offset} →
     * {@code messageId}（内核 {@code InboundMessage} 的 javadoc 明文："Kafka 为 topic-partition-offset"，
     * 所以这个拼法不是本类的发明）；{@code headers} → {@code headers}（走 {@link #userHeaders}，
     * 与写方向同一套规则）。
     *
     * <p><b>载荷不做第二次解码</b>：出站写的是 {@code String} 载荷 + Boot 缺省的 {@code StringSerializer}
     * （Task 37 的 KM2 与 Step 1 闭包事实），所以入站拿到的 {@code value} 运行时就是 {@code String}——
     * 与 RocketMQ 侧不同（那边厂商给 {@code byte[]}，必须 {@code new String(body, UTF_8)}）。
     * 这里再 {@code getBytes} 一次就是凭空造一次编码往返，故 {@code payload} 与 {@code record.value()}
     * 必须是同一个串（WF10 断 {@code assertSame}）。
     *
     * <p><b>两条失败都是"部署错"而不是"消息坏"</b>，所以都要说清该改哪一处：
     * ① {@code value == null}——Kafka 的墓碑记录（日志压缩的产物，实测能正常构造），它不是业务事件；
     * ② {@code value} 不是 {@code String}——部署把 {@code spring.kafka.consumer.value-deserializer} 换掉了。
     * 两者都抛 {@link FrameworkException}，而不是让内核的 {@code requireNonNull} 或厂商的
     * {@code ClassCastException} 成为调用方看到的第一手信息（那两个都只说"值不对"，不说"去哪改"）。
     *
     * <p><b>不做的事，四条</b>：① 不判 {@code headers()} 是否为 null（厂商构造器保证非 null，与 RocketMQ 侧
     * {@code getProperties()} 可能是 null 相反）；② 不校验 topic 形态（厂商在建容器时就拒空白，名字规则只有一份）；
     * ③ 不把 {@code key}、{@code timestamp}、{@code partition} 塞成消息头（内核 {@code MessageHeaderNames}
     * 没有这些名字，凭空加就是改冻结面）；④ 不读 {@code deliveryCount()}（重投节奏归厂商 handler）。
     *
     * @param record 厂商入站记录，不可为 {@code null}
     * @return 内核入站消息
     * @throws NullPointerException     入参为 {@code null}（消息含参数名 {@code record}）
     * @throws FrameworkException       记录是墓碑（{@code value=null}）或载荷不是 {@code String}，两条消息都含 topic、partition、offset
     * @throws IllegalArgumentException 通道名空白（由内核 record 抛出，本类不重复实现）
     */
    public static InboundMessage toInboundMessage(ConsumerRecord<?, ?> record) {
        Objects.requireNonNull(record, "record 不能为 null");
        Object value = record.value();
        if (value == null) {
            throw new FrameworkException("Kafka 投递了一条 value=null 的墓碑记录，无法转成 InboundMessage："
                    + "topic=" + record.topic() + "，partition=" + record.partition() + "，offset=" + record.offset()
                    + "（墓碑是日志压缩留下的删除标记，不是业务事件；本框架的入站载荷必须是 String，"
                    + "重投也变不出载荷。该 topic 若确实有压缩语义，请不要把它挂到 @MessageListener 上）");
        }
        if (!(value instanceof String payload)) {
            throw new FrameworkException("Kafka 记录的载荷类型是 " + value.getClass().getName()
                    + "，本框架的入站契约是 String 载荷：topic=" + record.topic()
                    + "，partition=" + record.partition() + "，offset=" + record.offset()
                    + "（期望形态：spring.kafka.consumer.value-deserializer="
                    + "org.apache.kafka.common.serialization.StringDeserializer——出站写的就是 String，见 Task 37 的 KM2。"
                    + "确实要消费 Avro/JSON 对象的 topic，请在业务侧自建容器与监听器，不要把它交给内核 @MessageListener）");
        }
        return new InboundMessage(record.topic(), payload,
                record.topic() + "-" + record.partition() + "-" + record.offset(),
                userHeaders(record.headers()));
    }
}
