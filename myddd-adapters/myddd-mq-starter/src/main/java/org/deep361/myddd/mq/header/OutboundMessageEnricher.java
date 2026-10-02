package org.deep361.myddd.mq.header;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.trace.TraceContext;
import org.deep361.myddd.kernel.port.message.MessageHeaderNames;
import org.deep361.myddd.kernel.port.message.OutboundMessage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 出站消息的框架头处理单点：写 traceId 头 + 拒绝厂商承载不了的空白头。
 *
 * <p><b>由谁调用</b>：每个 {@code ProviderMessagePort} 实现（Task 35/37）在把消息交给厂商客户端<b>之前</b>调用它，
 * <b>不是</b> {@code RoutingMessagePort}。理由与 Task 32 的契约基线同源：契约测试直接驱动 provider 侧实现
 * （MQ-C5 要断言 traceId 头真的在线上），如果注入点在路由层，单独验收任何一个 provider 都会缺这条头。
 * 路由层的职责到"选定 provider"为止（Task 33 的边界声明）。
 *
 * <p><b>三条规则</b>：① 调用方已经写了 {@code myddd-trace-id} 就不覆盖（显式优先于隐式）；
 * ② 当前线程没有 traceId 就<b>不伪造</b>——发送侧不是入站边界，伪造会把"这条消息没有链路上下文"
 * 伪装成"有"，而 {@code InboundDispatcher} 在消费侧该做的恰恰是补上（那里才是新链路的起点）；
 * ③ 除 traceId 外不碰任何字段（{@code eventId}/{@code eventType} 由 Outbox 投递器写，见 Task 58）。
 *
 * <p><b>为什么在这里拒绝空白头</b>：厂商承载能力不一致是实测事实——RocketMQ 的
 * {@code Message.putUserProperty} 对空白名/空白值抛
 * {@code IllegalArgumentException("The name or value of property can not be null or blank string!")}（本机 5.5.1 一手实测），
 * 而 Kafka 允许空字节头。留到厂商层就会变成"同一消息在两个 provider 上一投一抛"，等价性测试（Task 39）无解。
 * 框架层的结论只能是：这种消息<b>在上线前</b>就被拒绝，且拒绝理由与厂商无关。
 *
 * <p><b>{@code myddd.mq.trace-header.enabled=false} 的语义</b>：只做"不写 traceId"，空白头拒绝与其余整形照旧。
 * 关掉后消费端读不到上游 traceId、链路在 MQ 这一跳断开，属于诊断用途（README 里要写清这条）。
 *
 * <p>无可变状态（开关是 {@code final} 字段），线程安全；不是 Spring bean 也能用（契约测试与假适配器直接 {@code new}）。
 */
public final class OutboundMessageEnricher {

    private final boolean traceHeaderEnabled;

    /** 缺省构造：写 traceId 头（{@code myddd.mq.trace-header.enabled} 的 true 分支）。 */
    public OutboundMessageEnricher() {
        this(true);
    }

    /**
     * @param traceHeaderEnabled {@code false}＝跳过 traceId 注入（其余整形与拒绝不变），由自动装配从配置传入
     */
    public OutboundMessageEnricher(boolean traceHeaderEnabled) {
        this.traceHeaderEnabled = traceHeaderEnabled;
    }

    /**
     * 返回整形后的消息；无需改动时<b>返回入参本身</b>（同实例，零拷贝）。
     *
     * @param message 待发送消息，不可为 {@code null}
     * @return 新消息（需要注入 traceId 时）或入参本身
     * @throws NullPointerException  入参为 {@code null}
     * @throws BizException          消息含空白的头名或头值（{@link CommonErrorCode#ARGUMENT_INVALID}，
     *                               消息含具体头名）
     */
    public OutboundMessage enrich(OutboundMessage message) {
        Objects.requireNonNull(message, "message 不能为 null");
        rejectBlankHeaders(message);
        if (!traceHeaderEnabled || message.header(MessageHeaderNames.TRACE_ID).isPresent()) {
            return message;
        }
        String traceId = TraceContext.currentTraceId();
        if (traceId == null) {
            return message;
        }
        Map<String, String> headers = new LinkedHashMap<>(message.headers());
        headers.put(MessageHeaderNames.TRACE_ID, traceId);
        return new OutboundMessage(message.channel(), message.payload(), message.key(), headers, message.deliverAt());
    }

    private static void rejectBlankHeaders(OutboundMessage message) {
        message.headers().forEach((name, value) -> {
            if (name.isBlank() || value.isBlank()) {
                throw new BizException(CommonErrorCode.ARGUMENT_INVALID,
                        "消息头 " + (name.isBlank() ? "(空白头名)" : name));
            }
        });
    }
}
