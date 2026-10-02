package org.deep361.myddd.mq.rocketmq.produce;

import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.message.OutboundMessage;
import org.deep361.myddd.mq.MqProvider;
import org.deep361.myddd.mq.ProviderMessagePort;
import org.deep361.myddd.mq.header.OutboundMessageEnricher;
import org.deep361.myddd.mq.rocketmq.RocketMqProducer;
import org.deep361.myddd.mq.rocketmq.RocketMqWireFormat;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * {@code MessagePort} 的 RocketMQ 实现：把 {@link OutboundMessage} 翻译成厂商 {@code Message} 并同步发送。
 *
 * <p>本类<b>不做</b>的事，全部有主：路由（{@code RoutingMessagePort}，Task 33）、延迟开关
 * （{@code myddd.mq.deliver-at.enabled}，Task 33）、出站头策略（{@link OutboundMessageEnricher}，Task 34）、
 * 载荷序列化（{@code MessagePayloadConverter}，Task 34——内核规定载荷是 {@code String}，序列化是调用方的事）。
 * 本类只负责"框架消息 ⟷ RocketMQ 线上格式"这一条边，加上一件事：把厂商的失败说成一句能行动的话。
 *
 * <p>线程安全：{@code DefaultMQProducer} 本身线程安全（内核 {@code MessagePort} 契约 (b) 的前提），
 * 本类无可变状态（{@code KEY_SELECTOR} 是无状态常量）。
 *
 * <p>刻意<b>不</b>用的厂商能力，逐条给理由：
 * {@code sendOneway}（丢消息换吞吐，与 Outbox 的"至少一次"矛盾）、
 * {@code send(Collection)}（批量会把一条坏消息的成本摊到整批，且内核端口没有批量入口）、
 * {@code sendMessageInTransaction}（半消息需要 broker 回查本地事务状态，框架没有这张状态表）、
 * {@code setDelayTimeLevel}（18 个定级 ≠ {@code deliverAt} 的"时刻"语义）、
 * {@code setTags}（{@code @MessageListener} 没有 tag 维度，Task 36 的订阅一律 {@code "*"}；
 * 让业务头变成订阅过滤条件，等于凭空造出一个"谁能收到"的隐式开关）、
 * ACL/RPC hook（R3 禁 {@code rocketmq-acl}，认证留在 broker 与网络层）。
 */
public final class RocketMqMessagePort implements ProviderMessagePort {

    /**
     * 内核 {@code OutboundMessage.key} 的 javadoc（冻结文本）承诺"相同 key 的消息在同一分区（Kafka）
     * 或同一队列（RocketMQ）内有序"。RocketMQ 的 {@code KEYS} 属性只是查询索引，<b>不</b>决定队列——
     * 兑现这句承诺只有一条路：走 {@code send(Message, MessageQueueSelector, Object)}。
     *
     * <p>{@code floorMod} 而不是 {@code Math.abs(h) % n}：{@code "polygenelubricants".hashCode()}
     * 恰为 {@code Integer.MIN_VALUE}，而 {@code Math.abs(Integer.MIN_VALUE)} 仍是负数，
     * 下一次 {@code queues.get(负数)} 直接 {@code IndexOutOfBoundsException}——这是本框架唯一能
     * 把"哈希边缘值"钉住的写法，SEL3 用例先断言这个 hashCode 事实再断言落点。
     *
     * <p>队列数变化（broker 扩缩容）会让同一个 key 换队列，这是 RocketMQ 的固有性质，
     * 框架不承诺跨扩容的有序性；README 的配置参考表要写明（Task 59）。
     */
    private static final MessageQueueSelector KEY_SELECTOR =
            (queues, message, argument) -> queues.get(Math.floorMod(argument.hashCode(), queues.size()));

    private final RocketMqProducer producer;
    private final OutboundMessageEnricher enricher;

    public RocketMqMessagePort(RocketMqProducer producer, OutboundMessageEnricher enricher) {
        this.producer = Objects.requireNonNull(producer, "producer 不能为 null");
        this.enricher = Objects.requireNonNull(enricher, "enricher 不能为 null");
    }

    @Override
    public MqProvider provider() {
        return MqProvider.ROCKETMQ;
    }

    /** 5.x 定时消息（{@code Message.setDeliverTimeMs} → 属性 {@code TIMER_DELIVER_MS}，实测）。 */
    @Override
    public boolean supportsDelayedDelivery() {
        return true;
    }

    @Override
    public void send(OutboundMessage message) {
        Objects.requireNonNull(message, "message 不能为 null");
        OutboundMessage enriched = enricher.enrich(message);
        String key = keyOrNull(enriched);
        Message wire = toWireMessage(enriched, key);
        SendResult result = sendWire(wire, key);
        if (result.getSendStatus() != SendStatus.SEND_OK) {
            throw new FrameworkException("RocketMQ 未确认写入：" + describe(wire, key)
                    + "，发送状态 " + result.getSendStatus() + "，broker 返回 msgId " + result.getMsgId()
                    + "。非 SEND_OK 意味着消息可能未落盘或未同步到从节点，本框架一律按发送失败处理，由 Outbox 重投");
        }
    }

    /** 包私有：MAP/SEL 组逐字段断言映射结果，不经过发送。 */
    Message toWireMessage(OutboundMessage message, String key) {
        Message wire = new Message(message.channel(), message.payload().getBytes(StandardCharsets.UTF_8));
        if (key != null) {
            // 实测陷阱：setKeys(null) 会往属性表里写入 KEYS=null（而不是什么都不做），
            // 所以"无键"必须是"不调用"，不能是"传 null"。空串与纯空白同理。
            wire.setKeys(key);
        }
        message.headers().forEach((name, value) -> {
            if (RocketMqWireFormat.isSystemProperty(name)) {
                // 实测：厂商对保留名抛 RuntimeException("The Property<KEYS> is used by system, input another please")。
                // 先在这里拒绝，厂商异常就不会成为调用方看到的第一手失败。名单由 RocketMqWireFormat.isSystemProperty
                // 给出（STRING_HASH_SET 实测 54 项，那份事实钉在 RocketMqWireFormat 的类文档与本模块的 MAP8/WF9）。
                throw new BizException(CommonErrorCode.ARGUMENT_INVALID, "消息头 " + name
                        + " 是 RocketMQ 保留属性名");
            }
            wire.putUserProperty(name, value);
        });
        if (message.deliverAt() != null) {
            // 过去的时刻原样交给 broker（实测 setDeliverTimeMs 接受 0 与负值，不做校验），
            // 框架不替业务判断"这个时刻还有没有意义"。
            wire.setDeliverTimeMs(message.deliverAt().toEpochMilli());
        }
        return wire;
    }

    private SendResult sendWire(Message wire, String key) {
        try {
            return key == null ? producer.send(wire) : producer.send(wire, KEY_SELECTOR, key);
        } catch (MQClientException | RemotingException | MQBrokerException e) {
            throw new FrameworkException("RocketMQ 发送失败：" + describe(wire, key) + "，厂商原因：" + vendorReason(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FrameworkException("RocketMQ 发送被中断：" + describe(wire, key)
                    + "。中断标志已恢复，调用方（Outbox 投递器）据此结束本轮", e);
        } catch (RuntimeException e) {
            throw new FrameworkException("RocketMQ 抛出了未声明的运行时异常 " + e.getClass().getName() + "："
                    + describe(wire, key) + "，厂商原因：" + vendorReason(e), e);
        }
    }

    /** 空白 key 等价于无 key：内核 record 不校验 key，厂商的 KEYS 属性对空白毫无意义（实测 {@code KEYS=} 会照样写进属性表）。 */
    private static String keyOrNull(OutboundMessage message) {
        String key = message.key();
        return key == null || key.isBlank() ? null : key;
    }

    private static String describe(Message wire, String key) {
        return "topic=" + wire.getTopic() + (key == null ? "" : "，key=" + key);
    }

    private static String vendorReason(Throwable e) {
        if (e instanceof MQClientException client) {
            return client.getResponseCode() == 0 ? String.valueOf(client.getErrorMessage())
                    : "code=" + client.getResponseCode() + " " + client.getErrorMessage();
        }
        if (e instanceof MQBrokerException broker) {
            return "code=" + broker.getResponseCode() + " " + broker.getErrorMessage();
        }
        return e.getClass().getSimpleName() + " " + e.getMessage();
    }
}
