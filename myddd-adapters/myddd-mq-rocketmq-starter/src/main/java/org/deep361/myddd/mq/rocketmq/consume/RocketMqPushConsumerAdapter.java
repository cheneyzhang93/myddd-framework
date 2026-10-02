package org.deep361.myddd.mq.rocketmq.consume;

import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;

/**
 * 厂商 push consumer 的测试缝：本模块的消费侧用例全部离线执行，不需要 broker。
 *
 * <p><b>与 Task 35 的 {@code RocketMqProducer} 有一处刻意的不同：本接口不声明厂商受检异常。</b>
 * 理由是两者的可观察结果不同：producer 的失败是运行期、业务可见的（{@code send()} 的调用方要能区分
 * " broker 暂时不行"与"配置写错了"，所以缝保留厂商签名、翻译发生在端口里）；
 * 消费者的失败只有"应用起不来"这一种形态，翻译点放在实现侧反而能把 group、topic、name-server
 * 一次说清，生命周期层只需要接住"任何 {@code RuntimeException}"并补上它自己才知道的上下文。
 *
 * <p><b>缝的形状按厂商动作命名，而不是按框架概念命名</b>：{@code subscribe}/{@code registerMessageListener}/
 * {@code configure}/{@code start}/{@code close} 五个方法正好是一次消费者接线的全部步骤，
 * 夹具因此能原样记录"框架到底对厂商做了什么"。多加一层抽象（比如把注册与启动合并）就会让
 * CM 组无法断言顺序。
 */
public interface RocketMqPushConsumerAdapter extends AutoCloseable {

    /**
     * 订阅一个通道（= topic）。
     *
     * @param topic         通道名
     * @param subExpression 订阅表达式，本框架恒为 {@code SubscriptionData.SUB_ALL}（实测值 {@code *}）
     * @throws org.deep361.myddd.kernel.common.error.FrameworkException topic 名不合法（含厂商规则与实际值）
     */
    void subscribe(String topic, String subExpression);

    /** 注册并发消费回调（顺序由调用方保证：注册必须早于 {@link #start()}）。 */
    void registerMessageListener(MessageListenerConcurrently listener);

    /**
     * 应用框架配置的消费参数。
     *
     * @param maxReconsumeTimes     重投次数上限（{@code myddd.mq.rocketmq.consumer.max-reconsume-times}）
     * @param consumeTimeoutMinutes 消费超时，**单位分钟**（实测厂商把 {@code getConsumeTimeout()} 当分钟用）
     */
    void configure(int maxReconsumeTimes, long consumeTimeoutMinutes);

    /**
     * 启动消费者。
     *
     * @throws org.deep361.myddd.kernel.common.error.FrameworkException 厂商启动失败（含组、订阅通道、name-server）
     */
    void start();

    /** 关闭消费者。实测：未启动过、重复关闭都安全，因此调用方无需判断状态。 */
    @Override
    void close();
}
