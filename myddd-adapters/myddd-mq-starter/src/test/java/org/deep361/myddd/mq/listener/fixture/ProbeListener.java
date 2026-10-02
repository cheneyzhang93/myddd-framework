package org.deep361.myddd.mq.listener.fixture;

import org.deep361.myddd.kernel.common.trace.TraceContext;
import org.deep361.myddd.kernel.port.message.InboundMessage;
import org.deep361.myddd.kernel.port.message.MessageListener;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试夹具（非用例）：内核 {@code @MessageListener} 文档允许的两种参数形态各一条，供 LS/AB 直接断言。
 *
 * <p>两个通道刻意用不同的 group 写法（{@code onOrder} 写组、{@code onPayload} 不写），这样同一个夹具
 * 既服务"字段逐项对得上"（LS1），也服务"空串是缺省组标记而不是 null"（CP7）。
 */
public class ProbeListener {

    /** {@code InboundMessage} 参数形态的通道。 */
    public static final String INBOUND_CHANNEL = "order-events";
    /** 业务载荷参数形态的通道。 */
    public static final String PAYLOAD_CHANNEL = "order-paid";

    private final List<InboundMessage> inboundMessages = new CopyOnWriteArrayList<>();
    private final List<OrderPaid> payloads = new CopyOnWriteArrayList<>();
    private final List<String> tracesDuringCall = new CopyOnWriteArrayList<>();

    @MessageListener(channel = INBOUND_CHANNEL, group = "g-order", description = "入站消息形态的探针")
    public void onOrder(InboundMessage message) {
        inboundMessages.add(message);
        tracesDuringCall.add(TraceContext.currentTraceId());
    }

    @MessageListener(channel = PAYLOAD_CHANNEL, description = "业务载荷形态的探针")
    public void onPayload(OrderPaid payload) {
        payloads.add(payload);
        tracesDuringCall.add(TraceContext.currentTraceId());
    }

    public List<InboundMessage> inboundMessages() {
        return List.copyOf(inboundMessages);
    }

    public List<OrderPaid> payloads() {
        return List.copyOf(payloads);
    }

    /** 调用当下读到的 traceId：分发器的三件事里只有"链路绑定"需要在监听方法内部取证（AB3/AB6/AB7）。 */
    public List<String> tracesDuringCall() {
        return List.copyOf(tracesDuringCall);
    }

    /** 两个方法合计被调用几次（"零调用"是 AB8/AB14 的断言对象）。 */
    public int callCount() {
        return inboundMessages.size() + payloads.size();
    }

    /** 业务载荷类型：{@code int} 与 {@code Instant} 两个组件正好同时服务 PC4/PC5 的断言。 */
    public record OrderPaid(String orderId, int amount, Instant paidAt) {
    }
}
