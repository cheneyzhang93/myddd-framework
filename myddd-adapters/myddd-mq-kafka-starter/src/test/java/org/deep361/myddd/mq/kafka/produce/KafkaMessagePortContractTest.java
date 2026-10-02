package org.deep361.myddd.mq.kafka.produce;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.deep361.myddd.kernel.port.message.MessagePort;
import org.deep361.myddd.kernel.port.message.OutboundMessage;
import org.deep361.myddd.mq.header.OutboundMessageEnricher;
import org.deep361.myddd.mq.kafka.KafkaWireFormat;
import org.deep361.myddd.testing.contract.MessagePortContractTestBase;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.List;

/**
 * {@link MessagePortContractTestBase} 的 Kafka 实现：8 条契约用例读的是厂商 {@code ProducerRecord}。
 *
 * <p>{@link #sendAndReceive} 的"取回"读的是录制到的记录而不是 broker——与 RocketMQ 侧（Task 35 Step 9）
 * 同一个手法，两侧因此完全对称，Task 39 的等价性差异清单才有可比的对象。{@code MQ-C8} 在本侧走"必须拒发"
 * 分支，因为 {@link KafkaMessagePort#supportsDelayedDelivery()} 返回 {@code false}（能力声明与测试断言同源，
 * 不是子类凭空答一次）。
 *
 * <p>三处与那侧的读法差异，逐条核过而不是照抄：
 * ① {@code wire.value()} 本身就是 {@code String}（模板与记录都是 {@code <String,String>}），
 * 那侧要 {@code new String(body, UTF_8)}——两条断言等价，本侧不加恒等往返的噪音；
 * ② {@link #transportMessageCount()} <b>必须跨所有替身求和</b>：实测模板每次 send 都 {@code createProducer()}
 * （{@code k3out.txt} 的 {@code [createProducer, send] ×3}），第二条消息的计数落在另一个替身上，
 * 漏掉这条会让 {@code MQ-C7}（"校验失败不该碰运输"）恒读 0 而假绿；那侧一个 producer 走到底，不存在这个坑；
 * ③ 本侧没有"系统属性摘除"这一步：Kafka 没有保留字名单，{@link KafkaWireFormat#userHeaders} 因此是恒等映射，
 * {@code MQ-C4} 在这里只钉"名字与值原样"。
 */
class KafkaMessagePortContractTest extends MessagePortContractTestBase {

    private final RecordingKafkaProducerFactory factory = new RecordingKafkaProducerFactory();
    private final KafkaTemplate<String, String> template = RecordingKafkaProducerFactory.newTemplate(factory);
    private final KafkaMessagePort port = new KafkaMessagePort(template, new OutboundMessageEnricher());

    @Override
    protected MessagePort messagePort() {
        return port;
    }

    @Override
    protected Delivered sendAndReceive(OutboundMessage message, Duration timeout) {
        port.send(message);
        ProducerRecord<String, String> wire = lastWire();
        return new Delivered(wire.topic(), wire.key(), wire.value(), KafkaWireFormat.userHeaders(wire.headers()));
    }

    @Override
    protected int transportMessageCount() {
        return factory.all().stream().mapToInt(producer -> producer.sent().size()).sum();
    }

    @Override
    protected boolean supportsDelayedDelivery() {
        return port.supportsDelayedDelivery();
    }

    /**
     * 从最后一个<b>有记录</b>的替身取最后一条：倒着找而不是直接 {@code factory.last()}，
     * 因为"最近建出来的那个 producer"在失败注入的用例里可能一条都没收下（本类不涉及注入，
     * 但读法要和 {@code transportMessageCount()} 同一套，两处各读各的迟早分叉）。
     */
    private ProducerRecord<String, String> lastWire() {
        List<RecordingKafkaProducer> all = factory.all();
        for (int index = all.size() - 1; index >= 0; index--) {
            List<RecordingKafkaProducer.Sent> sent = all.get(index).sent();
            if (!sent.isEmpty()) {
                return sent.get(sent.size() - 1).record();
            }
        }
        throw new AssertionError("发送没有到达厂商侧");
    }
}
