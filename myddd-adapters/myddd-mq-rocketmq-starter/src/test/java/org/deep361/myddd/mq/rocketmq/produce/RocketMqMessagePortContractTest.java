package org.deep361.myddd.mq.rocketmq.produce;

import org.apache.rocketmq.common.message.Message;
import org.deep361.myddd.kernel.port.message.MessagePort;
import org.deep361.myddd.kernel.port.message.OutboundMessage;
import org.deep361.myddd.mq.header.OutboundMessageEnricher;
import org.deep361.myddd.testing.contract.MessagePortContractTestBase;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Task 32 的 {@link MessagePortContractTestBase} 在 RocketMQ 上的单测实现（进程内录制器，无 broker）。
 *
 * <p>Task 39 会把同一个基类再跑在真 broker 上（{@code RocketMqMessagePortIT}），两边共用
 * {@link RecordingRocketMqProducer#userHeaders(Message)} 这一份"厂商消息 → 可观察结果"的映射，
 * 等价性断言才不会分裂成两套读法。
 *
 * <p>基类的 8 条在这里全部通过，且没有一条需要"因为 RocketMQ 特殊所以放宽"——这正是
 * 契约基类的验收方式：它对两个 provider 提同一个要求，写不出第二个满足它的实现就说明端口设计有洞。
 */
class RocketMqMessagePortContractTest extends MessagePortContractTestBase {

    private final RecordingRocketMqProducer producer = new RecordingRocketMqProducer();
    private final RocketMqMessagePort port = new RocketMqMessagePort(producer, new OutboundMessageEnricher());

    @Override
    protected MessagePort messagePort() {
        return port;
    }

    @Override
    protected Delivered sendAndReceive(OutboundMessage message, Duration timeout) {
        port.send(message);
        if (producer.sent().isEmpty()) {
            throw new AssertionError("发送没有到达厂商侧：" + message.channel());
        }
        Message wire = producer.lastWire();
        return new Delivered(wire.getTopic(), wire.getKeys(),
                new String(wire.getBody(), StandardCharsets.UTF_8), RecordingRocketMqProducer.userHeaders(wire));
    }

    @Override
    protected int transportMessageCount() {
        return producer.sent().size();
    }

    @Override
    protected boolean supportsDelayedDelivery() {
        // 读端口自己声明的值，不是测试子类凭空答一次：Step 0 加宽接口的全部理由。
        return port.supportsDelayedDelivery();
    }
}
