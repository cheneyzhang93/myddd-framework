package org.deep361.myddd.mq.rocketmq.produce;

import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.deep361.myddd.mq.rocketmq.RocketMqProducer;
import org.deep361.myddd.mq.rocketmq.RocketMqWireFormat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * 厂商 producer 的录制替身：本模块 49 条用例里没有一条需要 broker。
 *
 * <p>它记录的是<b>厂商 {@code Message} 本身</b>而不是框架消息——这正是本模块的职责所在
 * （线上格式），断言因此能落在 {@code getTopic()}/{@code getKeys()}/{@code getBody()}/{@code getProperties()}
 * 这些真正会上飞机的字节上。
 */
public final class RecordingRocketMqProducer implements RocketMqProducer {

    /** 一次发送的完整观察：{@code selector}/{@code argument} 为 {@code null} 表示走了无键路径。 */
    public record Sent(Message message, MessageQueueSelector selector, Object argument) {
    }

    private final List<Sent> sent = new ArrayList<>();
    private final Deque<Throwable> failures = new ArrayDeque<>();
    private SendResult result = result(SendStatus.SEND_OK);

    /** 实测可离线构造：{@code SendResult(SendStatus, msgId, offsetMsgId, MessageQueue, queueOffset)}。 */
    public static SendResult result(SendStatus status) {
        return new SendResult(status, "msg-" + status, "offset-" + status,
                new MessageQueue("order-events", "broker-a", 0), 7L);
    }

    /**
     * 厂商 {@code Message} → 契约基类的 {@code Delivered} 所需的消息头视图。
     *
     * <p>判定源在 main 里（{@link org.deep361.myddd.mq.rocketmq.RocketMqWireFormat#userHeaders(Message)}）：
     * Task 36 的入站映射要用同一条"系统属性摘除"规则，夹具里再抄一份就等于把"两处真相迟早漂移"
     * 写进了测试基建。本方法只是委托。
     */
    public static Map<String, String> userHeaders(Message wire) {
        return RocketMqWireFormat.userHeaders(wire);
    }

    public List<Sent> sent() {
        return sent;
    }

    public Message lastWire() {
        return sent.get(sent.size() - 1).message();
    }

    /** 下一次 {@code send} 抛这个异常（受检的四种与运行时异常都支持）。 */
    public void failNextWith(Throwable failure) {
        failures.add(failure);
    }

    public void replyWith(SendResult result) {
        this.result = result;
    }

    @Override
    public SendResult send(Message message)
            throws MQClientException, RemotingException, MQBrokerException, InterruptedException {
        return record(message, null, null);
    }

    @Override
    public SendResult send(Message message, MessageQueueSelector selector, Object argument)
            throws MQClientException, RemotingException, MQBrokerException, InterruptedException {
        return record(message, selector, argument);
    }

    private SendResult record(Message message, MessageQueueSelector selector, Object argument)
            throws MQClientException, RemotingException, MQBrokerException, InterruptedException {
        Throwable failure = failures.poll();
        if (failure != null) {
            // 逐分支而不是 sneakyThrow：夹具只抛接口声明过的异常。哪天签名变了这里编译不过，
            // 那正是"翻译路径少了一条能被钉住的分支"的提醒。
            if (failure instanceof MQClientException e) {
                throw e;
            }
            if (failure instanceof RemotingException e) {          // 含 RemotingConnectException（实测其父类）
                throw e;
            }
            if (failure instanceof MQBrokerException e) {
                throw e;
            }
            if (failure instanceof InterruptedException e) {
                throw e;
            }
            if (failure instanceof RuntimeException e) {
                throw e;
            }
            if (failure instanceof Error e) {
                throw e;
            }
            throw new AssertionError("夹具不会抛 " + failure.getClass().getName(), failure);
        }
        sent.add(new Sent(message, selector, argument));
        return result;
    }
}
