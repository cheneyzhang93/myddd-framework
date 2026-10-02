package org.deep361.myddd.mq.rocketmq.produce;

import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.mq.rocketmq.RocketMqProducer;
import org.springframework.beans.factory.DisposableBean;

/**
 * {@link RocketMqProducer} 的厂商实现：持有并配置一个 {@code DefaultMQProducer}。
 *
 * <p><b>为什么惰性启动</b>（一手实测，5.5.1）：
 * ① {@code start()} 对不可达的 name-server <b>一律成功</b>（实测 {@code 127.0.0.1:19876}：start OK in
 * 1168 ms，state=RUNNING；失败发生在首次 {@code send}），所以"启动期就暴露配置错误"这个常见理由在
 * RocketMQ 上不成立——真正的配置错误由 {@code MydddRocketMqProperties} 的必填校验负责；
 * ② {@code start()} 要引导 netty 线程组并创建 {@code ~/rocketmqlogs}（构造阶段不建目录，实测），
 *   eager 启动等于往每次上下文启动塞进 1 秒多与一批线程，surefire 也躲不掉。
 * 于是本类在<b>第一次发送</b>时启动，且只启动一次；失败不缓存，下一次发送重试。
 *
 * <p>{@code synchronized} 而不是 CAS 标志：并发首发的第二个线程必须<b>等</b>第一个线程启动完，
 * 否则它会拿着 state=START_FAILED/CREATE_JUST 的生产者立刻收到一条"producer service state not OK"
 * （实测存在的失败形态），而那条消息对调用方毫无意义。
 *
 * <p>其余 producer 参数一律取厂商缺省（实测 {@code sendMsgTimeout=3000}、{@code retryTimesWhenSendFailed=2}、
 * {@code maxMessageSize=4194304}、{@code vipChannelEnabled=false}、{@code createTopicKey=TBW102}）：
 * §1.2.1 只给本模块两个键，不自造第三个；需要改这些的用户走
 * {@code @ConditionalOnMissingBean(RocketMqProducer.class)} 自带实现。
 * VIP 通道不改是双重的：厂商缺省已关，devbox 的 broker 也显式 {@code sendMessageWithVIPChannel=false}。
 */
public final class DefaultRocketMqProducer implements RocketMqProducer, DisposableBean {

    private final DefaultMQProducer producer;
    private boolean started;

    /**
     * 自动配置入口：只做 §1.2.1 钉住的两条配置 + 实例名唯一化。
     *
     * <p>{@code changeInstanceNameToPID()} 把实例名从厂商缺省的 {@code DEFAULT} 改成
     * {@code <pid>#<递增值>}（实测 {@code 23568#1180482457583800}，第二次调用得到 {@code …481356500}），
     * clientId 随之成为 {@code ip@pid#增值}。不这么做，同一 JVM 里的两个生产者（比如两个测试上下文，
     * 或生产者与 Task 36 的消费者）会共用一个 clientId 下的注册表。
     */
    public static DefaultRocketMqProducer of(String nameServer, String producerGroup) {
        DefaultMQProducer producer = new DefaultMQProducer(producerGroup);
        producer.setNamesrvAddr(nameServer);
        producer.changeInstanceNameToPID();
        return new DefaultRocketMqProducer(producer);
    }

    /** 给定现成厂商生产者的入口：包私有，测试用它注入计数/失败替身。 */
    DefaultRocketMqProducer(DefaultMQProducer producer) {
        this.producer = producer;
    }

    /** 包私有取证缝：PR1/PR2 断言配置落地，不启动。 */
    DefaultMQProducer producer() {
        return producer;
    }

    @Override
    public SendResult send(Message message)
            throws MQClientException, RemotingException, MQBrokerException, InterruptedException {
        ensureStarted();
        return producer.send(message);
    }

    @Override
    public SendResult send(Message message, MessageQueueSelector selector, Object argument)
            throws MQClientException, RemotingException, MQBrokerException, InterruptedException {
        ensureStarted();
        return producer.send(message, selector, argument);
    }

    @Override
    public void destroy() {
        producer.shutdown();
    }

    /** 只启动一次；启动失败不记状态，下一次发送重试（实测厂商会把失败状态留在生产者里）。 */
    private synchronized void ensureStarted() {
        if (started) {
            return;
        }
        try {
            producer.start();
            started = true;
        } catch (MQClientException e) {
            throw new FrameworkException("RocketMQ 生产者启动失败：producer-group=" + producer.getProducerGroup()
                    + "，name-server=" + producer.getNamesrvAddr() + "，厂商原因：" + vendorReason(e)
                    + "。下一次发送会重试启动", e);
        }
    }

    private static String vendorReason(MQClientException e) {
        return e.getResponseCode() == 0 ? String.valueOf(e.getErrorMessage())
                : "code=" + e.getResponseCode() + " " + e.getErrorMessage();
    }
}
