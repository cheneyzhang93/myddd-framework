package org.deep361.myddd.mq.rocketmq;

import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageExt;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.message.InboundMessage;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * RocketMQ 线上格式的单点：框架消息 ↔ 厂商 {@code Message}/{@code MessageExt}。
 *
 * <p><b>为什么存在</b>：出站（Task 35 的 {@code RocketMqMessagePort}）要"拒绝厂商保留的属性名"，
 * 入站（本类的 {@link #toInboundMessage(MessageExt)}）要"把厂商系统属性摘掉只留用户头"。
 * 两条规则的依据是同一个集合——{@code MessageConst.STRING_HASH_SET}（本机 5.5.1 实测
 * {@code size()==54}，含 {@code KEYS}/{@code TAGS}/{@code UNIQ_KEY}/{@code RECONSUME_TIME}/
 * {@code TIMER_DELIVER_MS}/{@code __BORNHOST} 等）。抄一份名单就是等着和厂商版本漂移，
 * 所以写与读都到这一个类里取。
 *
 * <p><b>通道名与 topic 逐字相同</b>：本框架不设置厂商的 {@code namespace}/{@code namespaceV2}
 * （{@link org.deep361.myddd.mq.rocketmq.config.MydddRocketMqProperties} 没有这两个键），
 * 因此出站写的 topic 与入站读到的 {@code getTopic()} 就是同一个通道名，路由表的键两侧对得上。
 * 这条不是风格：一旦有人加了 namespace，路由表与 {@code @MessageListener} 的通道名就会分叉，
 * 而分叉的表现是"消息发出去了没人收"。
 *
 * <p><b>载荷编码</b>：UTF-8 字节，与 Task 35 的出站方向同源；{@code InboundMessage} 的
 * {@code payload} 由内核 record 保证非 null，所以 {@link #toInboundMessage(MessageExt)} 必须在
 * 厂商侧载荷为 {@code null} 时先失败（实测新建的 {@code MessageExt} 其 {@code getBody()} 就是
 * {@code null}），不能让内核的 NPE 成为调用方看到的第一手信息。
 *
 * <p>纯函数、无可变状态，线程安全（厂商的消费者线程池并发调用）。
 */
public final class RocketMqWireFormat {

    private RocketMqWireFormat() {
    }

    /**
     * 是否为厂商保留的属性名。
     *
     * @param name 属性名，{@code null} 返回 {@code false}（实测 {@code contains(null)} 不抛）
     */
    public static boolean isSystemProperty(String name) {
        return MessageConst.STRING_HASH_SET.contains(name);
    }

    /**
     * 厂商消息的用户属性视图：系统属性摘除、{@code null} 值摘除，其余原样（名字与值都不改）。
     *
     * <p>{@code getProperties()} 可能是 {@code null}（实测新建 {@code MessageExt} 就是 {@code null}，
     * 而新建 {@code Message} 是 {@code {WAIT=true}}），本方法把它当作"没有用户头"处理。
     * 返回值是新的 {@code LinkedHashMap}，与厂商内部那张活的 map 无关（只读语义由调用方保证）。
     */
    public static Map<String, String> userHeaders(Message wire) {
        Objects.requireNonNull(wire, "wire 不能为 null");
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> properties = wire.getProperties();
        if (properties != null) {
            properties.forEach((name, value) -> {
                if (value != null && !isSystemProperty(name)) {
                    headers.put(name, value);
                }
            });
        }
        return headers;
    }

    /**
     * 厂商入站消息 → 内核 {@link InboundMessage}。
     *
     * <p>不做的事，三条都有理由：① 不把 {@code reconsumeTimes} 塞成消息头（内核
     * {@code MessageHeaderNames} 没有这个名字，凭空加一个头就是改冻结面；它只进日志，
     * 见 {@code RocketMqMessageListener}）；② 不校验通道名的厂商形态（那是订阅期的事，
     * 投递期报错只会把一条好消息变成坏消息）；③ 不碰 {@code tags}/{@code keys} 这类系统属性
     * （{@link #userHeaders(Message)} 已经摘除）。
     *
     * @param wire 厂商入站消息，不可为 {@code null}
     * @return 内核入站消息
     * @throws NullPointerException 入参为 {@code null}（消息含参数名 {@code wire}）
     * @throws FrameworkException   厂商侧载荷为 {@code null}（消息含 topic、msgId、reconsumeTimes）
     * @throws IllegalArgumentException 通道名空白或为 {@code null}（由内核 record 抛出，本类不重复实现）
     */
    public static InboundMessage toInboundMessage(MessageExt wire) {
        Objects.requireNonNull(wire, "wire 不能为 null");
        byte[] body = wire.getBody();
        if (body == null) {
            throw new FrameworkException("RocketMQ 投递了一条没有载荷的消息，无法转成 InboundMessage："
                    + "topic=" + wire.getTopic() + "，msgId=" + wire.getMsgId()
                    + "，reconsumeTimes=" + wire.getReconsumeTimes()
                    + "（内核 InboundMessage 的 payload 不可为 null；厂商侧载荷为 null 只可能是消息本身坏了，"
                    + "重投也修不好）");
        }
        return new InboundMessage(wire.getTopic(), new String(body, StandardCharsets.UTF_8),
                wire.getMsgId(), userHeaders(wire));
    }
}
