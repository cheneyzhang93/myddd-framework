package org.deep361.myddd.mq.rocketmq.consume;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.deep361.myddd.kernel.port.message.InboundMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 厂商并发消费回调的失败语义（LM1–LM8）：分发交给壳，本类只翻译消息形状与重投状态码。
 *
 * <p>用例全部走 {@link ConsumptionRig}（真注册表 + 真分发器 + 真消费计划 + 录制消费者），
 * 投递经 {@link RecordingRocketMqPushConsumer#deliver} 直接调框架注册进去的回调，
 * 与厂商线程池里的调用同形，一行 broker 都不需要。
 */
class RocketMqMessageListenerTest {

    private final ConsumptionRig rig = ConsumptionRig.rocketMq();

    @Test
    void should_dispatch_the_delivered_message_and_report_success() {
        InboundMessage received = deliverAndCapture("g-one",
                RecordingRocketMqPushConsumer.wire("a-channel", "{\"id\":1}", Map.of("x-a", "1")));

        assertThat(received.channel()).isEqualTo("a-channel");
        assertThat(received.payload()).isEqualTo("{\"id\":1}");
        assertThat(received.headers()).containsExactly(Map.entry("x-a", "1"));
    }

    @Test
    void should_ask_the_vendor_to_reconsume_when_the_listener_fails() {
        // 异常不外抛：厂商线程池拿到的是状态码而不是栈，这条断言就是那件事。
        assertThat(deliver("g-fail", RecordingRocketMqPushConsumer.wire("boom", "{}")))
                .isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        assertThat(rig.countFor("boom")).isEqualTo(1);       // 先记后抛：确实进过业务方法
    }

    @Test
    void should_not_translate_an_error_into_a_retry() {
        // Error 不是"消费失败"：重试的会是同一个已经撑不住的 JVM。catch(RuntimeException)→catch(Throwable) 的变异死在这条。
        assertThatThrownBy(() -> deliver("g-fail", RecordingRocketMqPushConsumer.wire("err", "{}")))
                .isInstanceOf(StackOverflowError.class);
    }

    @Test
    void should_stop_the_batch_at_the_first_failure() {
        // 三条同组同消费者＝厂商一个批次的投递形态；first 成功、boom 失败、third 不再被调。
        ConsumeConcurrentlyStatus status = deliver("g-fail",
                RecordingRocketMqPushConsumer.wire("first", "{}"),
                RecordingRocketMqPushConsumer.wire("boom", "{}"),
                RecordingRocketMqPushConsumer.wire("third", "{}"));

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        assertThat(rig.countFor("first")).isEqualTo(1);
        assertThat(rig.countFor("third")).isZero();          // 整批重投，而不是逐条尽力
    }

    @Test
    void should_report_success_for_an_empty_delivery() {
        assertThat(deliver("g-fail")).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        assertThat(rig.received).isEmpty();                  // for 循环的自然出口，没有额外判空
    }

    @Test
    void should_leave_the_reconsume_pace_to_the_broker() {
        RecordingRocketMqPushConsumer consumer = startedConsumer("g-fail");
        ConsumeConcurrentlyContext context = consumer.context();
        int delayLevelBefore = context.getDelayLevelWhenNextConsume();
        int ackIndexBefore = context.getAckIndex();

        consumer.deliver(RecordingRocketMqPushConsumer.wire("boom", "{}"));

        // 前后读的是同一个实例（夹具只建一次）：实测缺省 0 与 Integer.MAX_VALUE，框架一个都不改。
        assertThat(delayLevelBefore).isZero();
        assertThat(ackIndexBefore).isEqualTo(Integer.MAX_VALUE);
        assertThat(context.getDelayLevelWhenNextConsume()).isEqualTo(delayLevelBefore);
        assertThat(context.getAckIndex()).isEqualTo(ackIndexBefore);
    }

    @Test
    void should_reconsume_a_corrupted_message_instead_of_crashing_the_callback() {
        // 载荷 null → RocketMqWireFormat 的 FrameworkException 是 RuntimeException：重投而不是把异常递给厂商。
        assertThat(deliver("g-fail", RecordingRocketMqPushConsumer.wire("first", null)))
                .isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        assertThat(rig.countFor("first")).isZero();          // 映射失败的那条压根没进业务方法
    }

    @Test
    void should_reject_a_missing_collaborator() {
        assertThatThrownBy(() -> new RocketMqMessageListener(null, "g"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("dispatcher");
        assertThatThrownBy(() -> new RocketMqMessageListener(rig.dispatcher, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("consumerGroup");
    }

    /** 启动 manager（消费者只在 {@code start()} 里被创建）后取某个组的录制消费者。 */
    private RecordingRocketMqPushConsumer startedConsumer(String group) {
        rig.manager.start();
        return rig.consumer(group);
    }

    private ConsumeConcurrentlyStatus deliver(String group,
            org.apache.rocketmq.common.message.MessageExt... messages) {
        return startedConsumer(group).deliver(messages);
    }

    /** 投递并取回业务方法真正收到的那条内核消息（LM1 要的是 WF↔LM 接缝闭合）。 */
    private InboundMessage deliverAndCapture(String group,
            org.apache.rocketmq.common.message.MessageExt message) {
        assertThat(deliver(group, message)).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        assertThat(rig.received).hasSize(1);
        return rig.received.get(0);
    }
}
