package org.deep361.myddd.mq.rocketmq;

import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;

/**
 * {@code DefaultMQProducer} 的镜像接口：本模块里<b>只有这一个</b>类型能碰厂商 producer。
 *
 * <p>为什么要这层接口：厂商的 {@code send} 声明四个受检异常、需要一个活的 name-server，
 * 而 {@code RocketMqMessagePort} 的映射与失败翻译必须能在无 Docker 环境里逐条钉住（§0.3-7）。
 * 有了这条缝，49 条用例里的 25 条端口用例一条都不需要 broker，厂商异常类型还能原样出现在夹具里
 * （夹具不许 {@code instanceof} 厂商异常这件事只约束装配壳，provider 侧正是翻译的发生地）。
 *
 * <p>两个 {@code send} 重载与厂商签名一一对应（含 throws 列表），不做任何"顺手简化"：
 * 简化一次，{@code RocketMqMessagePort} 的异常翻译就少一条能被钉住的路径。
 * 生命周期（start/shutdown）<b>不在</b>这里——那是 {@code DefaultRocketMqProducer} 与 Spring 容器之间的事，
 * 自定义实现自负生命周期。
 */
public interface RocketMqProducer {

    /**
     * 无键发送：队列选择交给厂商缺省策略（轮询 + 跨 broker 重试）。
     *
     * @throws MQClientException  客户端侧失败（含生产者状态不对、无路由）
     * @throws RemotingException  网络失败
     * @throws MQBrokerException  broker 拒绝
     * @throws InterruptedException 发送等待被中断
     */
    SendResult send(Message message) throws MQClientException, RemotingException, MQBrokerException, InterruptedException;

    /**
     * 按键发送：厂商用 {@code selector} 在给定队列列表里选一个，相同参数 ⇒ 同一队列。
     *
     * @param argument 传给 selector 的参数，本框架固定为消息的 key（见 {@code RocketMqMessagePort} 的 SEL 组）
     */
    SendResult send(Message message, MessageQueueSelector selector, Object argument)
            throws MQClientException, RemotingException, MQBrokerException, InterruptedException;
}
