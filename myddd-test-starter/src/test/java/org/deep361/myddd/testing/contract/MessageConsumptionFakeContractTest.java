package org.deep361.myddd.testing.contract;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.message.InboundMessage;
import org.deep361.myddd.kernel.port.message.MessageHeaderNames;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@link MessageConsumptionContractTestBase} 的进程内实现：厂商线消息这一层整个省掉，
 * {@code Delivery} 的字段直接落成 {@link InboundMessage}。
 *
 * <p>它存在的唯一理由是<b>把基类与厂商解耦</b>：本模块的 classpath 上没有 rocketmq-client，也没有
 * spring-kafka（§0.3-7 的另一半：契约基类不许成为"没装 Docker 就跑不了"的东西）。
 *
 * <p><b>假适配器与真适配器的几处有意差异</b>（都不影响基类断言，影响的那部分基类压根没断）：
 * <ol>
 *   <li>不跑 {@code InboundDispatcher}：注册表与参数形态是 Task 34 的职责，这里重复一遍等于把同一件事钉两处。</li>
 *   <li>没有"组"的概念 ⇒ {@code MC-C8} 的兄弟通道只是两个互不相干的键。</li>
 *   <li>失败时 {@code surfaced} 直接给异常对象（真 RocketMQ 给的是状态码、拿不到异常），
 *       所以本类是基类那条 {@code if (surfaced != null)} 分支的真实覆盖来源。</li>
 *   <li>它<b>有一张自己的注册表</b>（{@link #KNOWN_CHANNELS}）：基类 {@code MC-C9} 要投一个"表外通道名"，
 *       没有这张表那条断言在假实现上就是空断言——这也是"假实现必须复刻被测系统的关键形状"这条纪律的落点。</li>
 * </ol>
 */
class MessageConsumptionFakeContractTest extends MessageConsumptionContractTestBase {

    private static final String PLAIN = "fake-plain";
    private static final String SIBLING = "fake-sibling";
    private static final String FAILING = "fake-failing";
    /** 假"注册表"：只有这三个通道有监听器。{@code MC-C9} 要的就是"表外通道必须被拒"，没有这张表那条断言在假实现上是空断言。 */
    private static final Set<String> KNOWN_CHANNELS = Set.of(PLAIN, SIBLING, FAILING);

    private final Map<String, Integer> invocations = new HashMap<>();
    private final RuntimeException failure = new IllegalStateException("假适配器遇到的业务失败");

    @Override
    protected String plainChannel() {
        return PLAIN;
    }

    @Override
    protected String siblingChannel() {
        return SIBLING;
    }

    @Override
    protected String failingChannel() {
        return FAILING;
    }

    @Override
    protected RuntimeException businessFailure() {
        return failure;
    }

    @Override
    protected int listenerInvocationsOn(String channel) {
        return invocations.getOrDefault(channel, 0);
    }

    @Override
    protected Observation deliver(Delivery delivery) {
        if (!KNOWN_CHANNELS.contains(delivery.channel())) {
            // 表外通道：一条都不投递，并把"没人听"如实报出去。真系统里这是分发器抛 FrameworkException
            //（Task 34 的既有语义），假实现用同一类型，不另造一种失败形态。
            return new Observation(null, FailureHandoff.TRANSPORT_INFORMED,
                    new FrameworkException("假注册表里没有通道 " + delivery.channel()), 0);
        }
        Map<String, String> headers = new LinkedHashMap<>(delivery.headers());
        if (delivery.eventId() != null) {
            headers.put(MessageHeaderNames.EVENT_ID, delivery.eventId());
        }
        if (delivery.traceId() != null) {
            headers.put(MessageHeaderNames.TRACE_ID, delivery.traceId());
        }
        InboundMessage message = new InboundMessage(delivery.channel(), delivery.payload(),
                "fake-" + delivery.channel(), headers);
        invocations.merge(delivery.channel(), 1, Integer::sum);
        // 假适配器把"业务失败"如实上抛，与 Kafka 侧"异常抵达错误处理器"同形。
        return delivery.failListener()
                ? new Observation(message, FailureHandoff.TRANSPORT_INFORMED, failure, 1)
                : new Observation(message, FailureHandoff.TRANSPORT_INFORMED, null, 1);
    }
}
