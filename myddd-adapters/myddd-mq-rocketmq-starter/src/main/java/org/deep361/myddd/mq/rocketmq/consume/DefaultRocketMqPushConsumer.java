package org.deep361.myddd.mq.rocketmq.consume;

import org.apache.rocketmq.client.Validators;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.deep361.myddd.kernel.common.error.FrameworkException;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link DefaultMQPushConsumer} 的框架接线。
 *
 * <p><b>校验借厂商的规则</b>：组名与 topic 名的形态检查用 {@code Validators.checkGroup}/{@code checkTopic}
 * （实测规则 {@code ^[%|a-zA-Z0-9_-]+$}、topic ≤ 127、group ≤ 120），框架不另立名单。
 * 但检查点必须自己选：实测 {@code subscribe()} 对非法 topic <b>不报错</b>，问题会拖到启动取路由时变成
 * 一句 connect 失败——所以这里在订阅前就检查，并把"含点号的通道名在 Kafka 合法、在 RocketMQ 非法"
 * 这条差异说清（DC3）。
 *
 * <p><b>不暴露的厂商参数</b>：{@code consumeFromWhere}、{@code messageModel}、{@code pullBatchSize}、
 * 消费线程数、{@code pullThresholdForQueue} 一律保持厂商缺省（实测
 * {@code CONSUME_FROM_LAST_OFFSET}/{@code CLUSTERING}/32/20/1000），DC6 逐条钉住。
 * 给它们配 {@code myddd.*} 键就是把厂商参数表翻译成框架键（§0.3-5 禁止），
 * 要改请贡献自己的 {@link RocketMqPushConsumerFactory}。
 */
public class DefaultRocketMqPushConsumer implements RocketMqPushConsumerAdapter {

    private final DefaultMQPushConsumer consumer;
    private final List<String> subscribed = new ArrayList<>();

    /**
     * @param nameServer    name-server 地址（由配置校验保证非空白）
     * @param consumerGroup 消费组名
     * @throws FrameworkException 组名不合法（含厂商规则与实际值，并点出两个可能的来源）
     */
    public DefaultRocketMqPushConsumer(String nameServer, String consumerGroup) {
        try {
            Validators.checkGroup(consumerGroup);
        } catch (MQClientException e) {
            throw new FrameworkException("消费组名不合法：" + consumerGroup + "，厂商规则：" + vendorReason(e)
                    + "。它来自 @MessageListener 的 group，或来自该通道未写 group 时的 "
                    + "myddd.mq.rocketmq.consumer.group", e);
        }
        DefaultMQPushConsumer created = new DefaultMQPushConsumer(consumerGroup);
        created.setNamesrvAddr(nameServer);
        // 实测：不改 instanceName 时它是 "DEFAULT"，同进程内多个消费者会共用一个 clientId 语义；
        // 与 Task 35 的 producer 同样调一次，得到 <pid>#<…> 形态的唯一实例名。
        created.changeInstanceNameToPID();
        this.consumer = created;
    }

    @Override
    public void subscribe(String topic, String subExpression) {
        try {
            Validators.checkTopic(topic);
        } catch (MQClientException e) {
            throw new FrameworkException("通道名不能用作 RocketMQ 的 topic：" + topic
                    + "，厂商规则：" + vendorReason(e)
                    + "。含点号的通道名在 Kafka 上合法、在 RocketMQ 上非法——这是两个 provider 的 topic 规则差异，"
                    + "只能改 @MessageListener 的 channel（改成 - 或 _ 分隔）", e);
        }
        try {
            consumer.subscribe(topic, subExpression);
            subscribed.add(topic);
        } catch (MQClientException e) {
            throw new FrameworkException("订阅失败：topic=" + topic + "，consumer-group="
                    + consumer.getConsumerGroup() + "，厂商原因：" + vendorReason(e), e);
        }
    }

    @Override
    public void registerMessageListener(MessageListenerConcurrently listener) {
        consumer.registerMessageListener(listener);
    }

    @Override
    public void configure(int maxReconsumeTimes, long consumeTimeoutMinutes) {
        consumer.setMaxReconsumeTimes(maxReconsumeTimes);
        consumer.setConsumeTimeout(consumeTimeoutMinutes);
    }

    /**
     * {@inheritDoc}
     *
     * <p>这里只翻译厂商<b>声明过</b>的 {@code MQClientException}。实测 name-server 不可达时厂商抛的是
     * <b>未声明的</b> {@code IllegalStateException}（cause {@code RemotingConnectException}），
     * 那条留给 {@code RocketMqConsumerManager} 处理——它还得把同批已启动的消费者关掉。
     */
    @Override
    public void start() {
        try {
            consumer.start();
        } catch (MQClientException e) {
            throw new FrameworkException("RocketMQ 消费者启动失败：consumer-group=" + consumer.getConsumerGroup()
                    + "，订阅通道=" + subscribed + "，name-server=" + consumer.getNamesrvAddr()
                    + "，厂商原因：" + vendorReason(e), e);
        }
    }

    @Override
    public void close() {
        consumer.shutdown();
    }

    /** 取证缝（与 Task 35 的 {@code producer()} 同形）：DC 组用它核对未暴露参数的缺省值。 */
    DefaultMQPushConsumer consumer() {
        return consumer;
    }

    /** 厂商消息第一行就是结论，后面的 "For more information, please visit the url, …" 是噪音（实测：{@code p9out.txt} 的 RAW 行）。 */
    private static String vendorReason(MQClientException failure) {
        String message = failure.getMessage();
        if (message == null) {
            return failure.getClass().getSimpleName();
        }
        int lineEnd = message.indexOf('\n');
        return lineEnd < 0 ? message : message.substring(0, lineEnd);
    }
}
