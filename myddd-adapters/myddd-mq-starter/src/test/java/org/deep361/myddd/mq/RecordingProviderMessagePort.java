package org.deep361.myddd.mq;

import org.deep361.myddd.kernel.port.message.OutboundMessage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试夹具（非用例）：记录收到过什么消息的假 provider 实现。
 *
 * <p>两个构造参数决定它报哪个 {@link MqProvider}、是否声明支持定点投递——后者是 Task 35 Step 0
 * 加进 SPI 的能力声明；壳里没有任何代码读它（同一个判断不许两处真相，最终拒发在 provider 侧），
 * 所以夹具只需要如实报告，壳的用例数不因它而变。
 */
public class RecordingProviderMessagePort implements ProviderMessagePort {

    private final MqProvider provider;
    private final boolean supportsDelayedDelivery;
    private final List<OutboundMessage> sent = new CopyOnWriteArrayList<>();

    public RecordingProviderMessagePort(MqProvider provider, boolean supportsDelayedDelivery) {
        this.provider = provider;
        this.supportsDelayedDelivery = supportsDelayedDelivery;
    }

    @Override
    public MqProvider provider() {
        return provider;
    }

    @Override
    public boolean supportsDelayedDelivery() {
        return supportsDelayedDelivery;
    }

    @Override
    public void send(OutboundMessage message) {
        sent.add(message);
    }

    /** 本 provider 收到过几条消息（RT/AC 两组的核心断言对象：不该收的一条都不许收）。 */
    public int sentCount() {
        return sent.size();
    }

    public List<OutboundMessage> sentMessages() {
        return List.copyOf(sent);
    }
}
