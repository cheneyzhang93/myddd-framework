package org.deep361.myddd.mq.kafka.consume;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.deep361.myddd.kernel.port.message.InboundMessage;
import org.deep361.myddd.kernel.port.message.MessageHeaderNames;
import org.deep361.myddd.testing.contract.MessageConsumptionContractTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kafka 消费适配器跑 {@link MessageConsumptionContractTestBase} 的九条契约。
 *
 * <p>与 RocketMQ 侧的<b>两处真实差异</b>（都在 {@code deliver} 里，基类看不到）：
 * <ol>
 *   <li>Kafka 的容器替身没有"状态码"这种东西：{@code deliver(record)} 直接把 {@code onMessage} 抛出来，
 *       所以"是否如实交给传输"的判据是<b>异常有没有逃出监听器</b>，而不是返回值。</li>
 *   <li>厂商线消息没有内部头可摘（DLT 那批 {@code kafka_dlt-*} 头只在恢复器里加，恢复不在这条路径上），
 *       所以 {@code MC-C5} 在本 provider 上是"确实无事可做"。这条事实记在不对称清单第 3 行，
 *       它同时说明为什么本类的九条必须与 RocketMQ 侧同跑一次：只有两边都绿，才能说清"漏摘"这件事
 *       只在一家会发生。</li>
 * </ol>
 *
 * <p><b>本 provider 答的不对称</b>：第 1 行（缺省链重试 9 次后 {@code commitSync} 跳过失败记录——由 K19 探针
 * 与 {@code LM} 组钉，基类不断）、第 5 行（异常对象在这里看得见，所以基类那条 {@code if} 分支由本类真实覆盖）、
 * 第 6 行（组名回落链：注解 → {@code spring.kafka.consumer.group-id} → 启动期抛）。
 */
class KafkaConsumptionContractTest extends MessageConsumptionContractTestBase {

    private final ConsumptionRig rig = ConsumptionRig.kafka("g-from-props");
    private final RecordingKafkaContainerFactory containerFactory = new RecordingKafkaContainerFactory();
    private final KafkaConsumerManager manager = new KafkaConsumerManager(containerFactory,
            rig.consumerFactory, rig.plan, rig.dispatcher);

    @BeforeEach
    void startManager() {
        manager.start();
    }

    @AfterEach
    void stopManager() {
        manager.stop();
    }

    /**
     * 通道 → 承载它的容器替身（manager 按组建容器，所以这里按订阅名单找，不按下标）。
     *
     * <p>找不到订阅者时借 {@code plainChannel()} 的那个容器投：<b>{@code MC-C9} 用的就是"没有任何容器
     * 订阅的通道名"</b>（全大写），而厂商实况是 broker 把 topic 名原样交给消费者、由<b>分发器</b>在"这个通道
     * 没人听"这件事上拒收。直接抛"找不到容器"等于把这条断言的观察点从分发器挪回了夹具自己。
     */
    private RecordingKafkaMessageListenerContainer containerFor(String channel) {
        return containerFactory.created().stream()
                .filter(container -> container.subscriptions().contains(channel))
                .findFirst()
                .orElseGet(() -> containerFor(plainChannel()));
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
        return (int) rig.received.stream().filter(message -> message.channel().equals(channel)).count();
    }

    @Override
    protected Observation deliver(Delivery delivery) {
        Map<String, String> headers = new LinkedHashMap<>(delivery.headers());
        if (delivery.eventId() != null) {
            headers.put(MessageHeaderNames.EVENT_ID, delivery.eventId());
        }
        if (delivery.traceId() != null) {
            headers.put(MessageHeaderNames.TRACE_ID, delivery.traceId());
        }
        int before = listenerInvocationsOn(delivery.channel());
        InboundMessage delivered;
        Throwable surfaced = null;
        boolean informed;
        try {
            ConsumerRecord<Object, Object> wire =
                    ConsumptionRig.wire(delivery.channel(), delivery.payload(), headers);
            containerFor(delivery.channel()).deliver(wire);
            delivered = lastMessageOf(delivery.channel());
            informed = !delivery.failListener();       // 该抛却没抛 ⇒ 被吞了
        } catch (RuntimeException failure) {
            surfaced = failure;
            delivered = lastMessageOf(delivery.channel());
            informed = true;                           // 异常抵达错误处理器 = 交给传输
        }
        int delta = listenerInvocationsOn(delivery.channel()) - before;
        return new Observation(delivered, informed ? FailureHandoff.TRANSPORT_INFORMED
                : FailureHandoff.SWALLOWED_AT_ADAPTER, surfaced, delta);
    }

    /** 该通道最近一条真到达监听器的消息；一条都没有时返回 {@code null}（{@code MC-C9} 的判据之一就是"没有消息到达"）。 */
    private InboundMessage lastMessageOf(String channel) {
        return rig.received.stream().filter(message -> message.channel().equals(channel))
                .reduce((older, newer) -> newer).orElse(null);
    }
}
