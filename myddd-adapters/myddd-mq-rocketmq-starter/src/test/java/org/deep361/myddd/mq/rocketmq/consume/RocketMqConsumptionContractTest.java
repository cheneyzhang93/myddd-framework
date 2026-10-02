package org.deep361.myddd.mq.rocketmq.consume;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.deep361.myddd.kernel.port.message.InboundMessage;
import org.deep361.myddd.kernel.port.message.MessageHeaderNames;
import org.deep361.myddd.testing.contract.MessageConsumptionContractTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RocketMQ 消费适配器跑 {@link MessageConsumptionContractTestBase} 的九条契约。
 *
 * <p><b>线消息一律用 {@code wireWithSystemProperties} 造</b>：它走厂商自己的 {@code setKeys}/{@code setTags}，
 * 属性名因此真在 {@code MessageConst.STRING_HASH_SET} 里（Task 36 的 {@code WF6} 读方向同款）。这是
 * {@code MC-C5}（厂商内部键不许漏进消息头）在本 provider 上<b>真有判据</b>的原因——换成普通 {@code wire}，
 * 那条断言就退化成"我数了自己塞进去的两个键"。
 *
 * <p><b>属性名与值只用 ASCII</b>：厂商的 {@code putUserProperty} 要过 {@code Validators.checkCharacter}，
 * 中文属性名当场抛（Task 36 取证表里那一条）。载荷可以中文——它走 body 字节，不经过属性表。
 *
 * <p><b>本 provider 答的不对称</b>（清单行号见 {@code docs/mq/asymmetry.md}）：第 3 行（内部键要摘，
 * 所以 {@code MC-C5} 在这里是真判据）、第 5 行（失败时 {@code surfaced} 恒为 {@code null}，seam 只是状态码）、
 * 第 6 行（组名来自注解或 {@code myddd.mq.rocketmq.consumer.group}，与 Kafka 的回落链不同源）。
 * 重投次数与耗尽落点（第 1 行）由 {@code CM}/{@code LM}/{@code DC} 组各自钉，基类一条都不碰。
 */
class RocketMqConsumptionContractTest extends MessageConsumptionContractTestBase {

    private static final String GROUP_ONE = "g-one";
    private static final String GROUP_FAIL = "g-fail";

    private final ConsumptionRig rig = ConsumptionRig.rocketMq();

    @BeforeEach
    void startManager() {
        rig.manager.start();
    }

    @AfterEach
    void stopManager() {
        rig.manager.stop();
    }

    /**
     * 夹具里的通道 → 它所属的组（一组一个消费者，见 Task 36 Step 8b 的组→通道清单）。
     *
     * <p>default 分支交 {@code GROUP_ONE} 而不是抛：<b>{@code MC-C9} 投的就是一个"表外通道名"</b>
     * （全大写的那个），它必须经一个真在跑的消费者进去，才能观察到"分发器拒收"。这里的兜底不影响
     * 通道归属——消费者只负责把线消息交给分发器，落到哪个组不影响那条断言。
     */
    private static String groupOf(String channel) {
        return switch (channel) {
            case "a-channel", "b-channel", "c-channel" -> GROUP_ONE;
            case "boom", "err", "first", "third" -> GROUP_FAIL;
            default -> GROUP_ONE;
        };
    }

    @Override
    protected String plainChannel() {
        return "a-channel";
    }

    @Override
    protected String siblingChannel() {
        return "b-channel";
    }

    @Override
    protected String failingChannel() {
        return "boom";
    }

    @Override
    protected RuntimeException businessFailure() {
        return new IllegalStateException("业务失败");
    }

    @Override
    protected int listenerInvocationsOn(String channel) {
        return rig.countFor(channel);
    }

    private static Map<String, String> headersOf(Delivery delivery) {
        Map<String, String> headers = new LinkedHashMap<>(delivery.headers());
        if (delivery.eventId() != null) {
            headers.put(MessageHeaderNames.EVENT_ID, delivery.eventId());
        }
        if (delivery.traceId() != null) {
            headers.put(MessageHeaderNames.TRACE_ID, delivery.traceId());
        }
        return headers;
    }

    /** 该通道最近一条真到达监听器的消息；一条都没有时返回 {@code null}（基类的 {@code delivered} 允许为 null，{@code MC-C9} 就要它为空）。 */
    private InboundMessage lastMessageOf(String channel) {
        return rig.received.stream().filter(message -> message.channel().equals(channel))
                .reduce((older, newer) -> newer).orElse(null);
    }

    @Override
    protected Observation deliver(Delivery delivery) {
        MessageExt wire = RecordingRocketMqPushConsumer.wireWithSystemProperties(delivery.channel(),
                delivery.payload(), "contract-keys", "TAG_CONTRACT", headersOf(delivery));
        int before = rig.countFor(delivery.channel());
        RecordingRocketMqPushConsumer consumer = rig.consumer(groupOf(delivery.channel()));
        Throwable surfaced = null;
        boolean informed;
        try {
            // 实测：厂商回调拿不到异常，只拿到状态码（Task 36 的 LM2）。所以 informed 由状态码判定。
            informed = consumer.deliver(wire) == ConsumeConcurrentlyStatus.RECONSUME_LATER;
        } catch (RuntimeException failure) {
            surfaced = failure;
            informed = true;
        }
        int delta = rig.countFor(delivery.channel()) - before;
        if (!informed && delivery.failListener()) {
            // 该失败却回了 CONSUME_SUCCESS ⇒ 消息静默丢失，这正是 MC-C7 要抓的形状。
            return new Observation(lastMessageOf(delivery.channel()), FailureHandoff.SWALLOWED_AT_ADAPTER,
                    surfaced, delta);
        }
        return new Observation(lastMessageOf(delivery.channel()), FailureHandoff.TRANSPORT_INFORMED, surfaced, delta);
    }
}
