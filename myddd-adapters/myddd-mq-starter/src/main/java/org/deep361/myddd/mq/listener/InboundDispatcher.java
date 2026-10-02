package org.deep361.myddd.mq.listener;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.common.trace.TraceContext;
import org.deep361.myddd.kernel.port.message.InboundMessage;
import org.deep361.myddd.kernel.port.message.MessageHeaderNames;
import org.deep361.myddd.mq.payload.MessagePayloadConverter;

import java.util.List;
import java.util.Objects;

/**
 * 入站分发：一条 {@link InboundMessage} → 该通道的全部监听方法。
 *
 * <p>适配器只做两件事：把厂商消息翻译成 {@link InboundMessage}，然后调用本类；本类返回或抛出后，
 * 适配器按结果决定 ack 还是重投（RocketMQ 的 {@code RECONSUME_LATER} / Kafka 的不提交 offset）。
 * 所以本类的语义必须与厂商无关，且三件事都在这里做完：
 * <ol>
 *   <li><b>链路</b>：消息头里有 traceId 就用它（空白按"没有"处理），没有就在本线程生成；
 *       调用结束后<b>恢复调用线程的原值</b>——原值为空才 clear。消费者线程是复用线程，
 *       直接 clear 会把同线程上一条消息的上下文也抹掉，而重新 set 原值才能兼容"同进程内投递"的假适配器。</li>
 *   <li><b>参数形态</b>：{@code InboundMessage} 参数原样传入，业务载荷参数经
 *       {@link MessagePayloadConverter#read(String, Class)} 转换。</li>
 *   <li><b>失败即整体失败</b>：按快照顺序逐个调用，第一个抛异常就往上传播、后续不再调用。
 *       重投会让已成功的监听器再执行一次，这是 at-least-once 的既有代价，由消费端幂等兜底
 *       （{@code IdempotentPort}／{@link MessageHeaderNames#EVENT_ID}），而不是在这里做"部分成功"的假象。</li>
 * </ol>
 *
 * <p>无可变状态，线程安全（厂商的消费者线程池可并发调用）。
 */
public final class InboundDispatcher {

    private final MessageListenerRegistrar registrar;
    private final MessagePayloadConverter converter;

    /**
     * @param registrar 监听器注册表，不可为 {@code null}
     * @param converter 载荷转换器，不可为 {@code null}（只有 {@code InboundMessage} 参数形态时不使用，但缺失不允许）
     */
    public InboundDispatcher(MessageListenerRegistrar registrar, MessagePayloadConverter converter) {
        this.registrar = Objects.requireNonNull(registrar, "registrar 不能为 null");
        this.converter = Objects.requireNonNull(converter, "converter 不能为 null");
    }

    /**
     * 分发给该通道的全部监听方法。
     *
     * @param message 入站消息，不可为 {@code null}
     * @return 实际被调用的监听方法数（全部成功时才返回）
     * @throws NullPointerException 入参为 {@code null}
     * @throws FrameworkException   该通道没有任何监听器（订阅关系与注册表漂移），或载荷反序列化失败
     * @throws RuntimeException     监听方法自己的异常（原样传播，交给适配器的重试策略）
     */
    public int dispatch(InboundMessage message) {
        Objects.requireNonNull(message, "message 不能为 null");
        List<RegisteredMessageListener> targets = registrar.listenersFor(message.channel());
        if (targets.isEmpty()) {
            throw new FrameworkException("通道 " + message.channel() + " 收到了一条消息，但没有任何 @MessageListener"
                    + "（已注册通道：" + registrar.channels() + "）。订阅关系应由注册表决定，出现这条消息说明"
                    + "适配器订阅了一个本进程无人监听的通道");
        }
        String previous = TraceContext.currentTraceId();
        String traceId = message.traceId().filter(value -> !value.isBlank()).orElse(null);
        try {
            if (traceId == null) {
                TraceContext.ensureTraceId();
            } else {
                TraceContext.set(traceId);
            }
            for (RegisteredMessageListener target : targets) {
                target.invoke(target.consumesInboundMessage() ? message
                        : converter.read(message.payload(), target.parameterType()));
            }
        } finally {
            if (previous == null) {
                TraceContext.clear();
            } else {
                TraceContext.set(previous);
            }
        }
        return targets.size();
    }
}
