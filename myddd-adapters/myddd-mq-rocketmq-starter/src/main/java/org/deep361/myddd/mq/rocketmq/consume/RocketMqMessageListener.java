package org.deep361.myddd.mq.rocketmq.consume;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.deep361.myddd.kernel.port.message.InboundMessage;
import org.deep361.myddd.mq.listener.InboundDispatcher;
import org.deep361.myddd.mq.rocketmq.RocketMqWireFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 厂商并发消费回调：一条 {@code MessageExt} → {@link InboundDispatcher#dispatch(InboundMessage)} → 状态码。
 *
 * <p><b>只翻译两件事</b>：消息形状（{@link RocketMqWireFormat#toInboundMessage(MessageExt)}）与
 * 失败语义（{@link ConsumeConcurrentlyStatus}）。分发顺序、traceId 绑定、参数形态转换全在壳里，
 * 这里一律不复制（R1：适配器薄，语义在壳）。
 *
 * <p><b>失败就整条重投</b>：{@code RuntimeException} 一律 {@code RECONSUME_LATER}，交给厂商的节奏
 * （实测：{@code ConsumeConcurrentlyContext} 缺省 {@code delayLevelWhenNextConsume=0}，即"用 broker 的
 * 缺省梯度"），超过 {@code maxReconsumeTimes} 后由 broker 投到 {@code %DLQ%<消费组>}
 * （实测 {@code MixAll.getDLQTopic("g1")=="%DLQ%g1"}）。本框架<b>不</b>读 {@code reconsumeTimes}
 * 来做"第 N 次就放弃"的判断：重投上限是配置里的一个数，再加一层自造策略就成了两套真相。
 *
 * <p><b>{@code Error} 不捕获</b>：{@code OutOfMemoryError}/{@code StackOverflowError} 不是"消费失败"，
 * 把它翻译成 {@code RECONSUME_LATER} 等于宣布"再试一次"，而重试的正是同一个已经撑不住的 JVM。
 * 让厂商自己处置它自己的回调异常（内核 {@code @MessageListener} 文档只承诺"方法抛出的异常视为消费失败"，
 * {@code Error} 不在这句话里）。
 *
 * <p><b>批量</b>：厂商缺省 {@code consumeMessageBatchMaxSize=1}（实测），本框架不改它；
 * 但循环照写——业务若贡献了自己的工厂，批量 > 1 时语义仍是"第一条失败就整批重投"，
 * 而"重投会让已成功的监听器再跑一次"是 at-least-once 的既有代价（壳的 {@code InboundDispatcher} javadoc 已写明，
 * 幂等由 {@code MessageHeaderNames#EVENT_ID} 兜底）。
 */
public final class RocketMqMessageListener implements MessageListenerConcurrently {

    private static final Logger log = LoggerFactory.getLogger(RocketMqMessageListener.class);

    private final InboundDispatcher dispatcher;
    private final String consumerGroup;

    /**
     * @param dispatcher    壳的入站分发器，不可为 {@code null}
     * @param consumerGroup 消费组名，仅用于诊断日志
     */
    public RocketMqMessageListener(InboundDispatcher dispatcher, String consumerGroup) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher 不能为 null");
        this.consumerGroup = Objects.requireNonNull(consumerGroup, "consumerGroup 不能为 null");
    }

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages, ConsumeConcurrentlyContext context) {
        for (MessageExt message : messages) {
            try {
                dispatcher.dispatch(RocketMqWireFormat.toInboundMessage(message));
            } catch (RuntimeException failure) {
                // context 一次都不读：改 delayLevelWhenNextConsume 就等于框架接管重投节奏（LM6 钉住缺省 0）。
                log.warn("RocketMQ 消费失败，交给厂商重投：consumer-group={}，topic={}，msgId={}，"
                                + "reconsumeTimes={}，keys={}", consumerGroup, message.getTopic(),
                        message.getMsgId(), message.getReconsumeTimes(), message.getKeys(), failure);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}
