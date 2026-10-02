package org.deep361.myddd.testing.contract;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.trace.TraceContext;
import org.deep361.myddd.kernel.port.message.MessageHeaderNames;
import org.deep361.myddd.kernel.port.message.MessagePort;
import org.deep361.myddd.kernel.port.message.OutboundMessage;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link MessagePortContractTestBase} 的进程内夹具（Task 32 Step 13 要求的配套交付物）：
 * 用一份不碰中间件的实现继承基类，证明基类的每条断言都有 provider 能同时满足。
 */
class MessagePortFakeContractTest extends MessagePortContractTestBase {

    private final Deque<OutboundMessage> sent = new ArrayDeque<>();
    private final MessagePort port = this::record;

    /**
     * 录制器：只接受"立即投递"的消息——本夹具声明 {@code supportsDelayedDelivery() == false}（Kafka 形态）。
     *
     * <p>trace 头在这里补写：MQ-C5 判的就是"适配器在出站那一刻从 {@link TraceContext} 取值盖头"，夹具扮演
     * provider，不写就等于让基类跑在一条没人实现的能力上。空白通道一类入参校验刻意<b>不</b>重复：那由内核
     * {@link OutboundMessage} 的紧凑构造器负责，夹具再挡一次会让 MQ-C7 的"transport 一条都没碰"变成假绿。
     */
    private void record(OutboundMessage message) {
        if (message.deliverAt() != null) {
            throw new BizException(CommonErrorCode.ARGUMENT_INVALID, "deliverAt");
        }
        sent.add(stampTraceHeader(message));
    }

    private static OutboundMessage stampTraceHeader(OutboundMessage message) {
        String traceId = TraceContext.currentTraceId();
        if (traceId == null || message.headers().containsKey(MessageHeaderNames.TRACE_ID)) {
            return message;
        }
        Map<String, String> headers = new LinkedHashMap<>(message.headers());
        headers.put(MessageHeaderNames.TRACE_ID, traceId);
        return new OutboundMessage(message.channel(), message.payload(), message.key(), headers,
                message.deliverAt());
    }

    @Override
    protected MessagePort messagePort() {
        return port;
    }

    @Override
    protected Delivered sendAndReceive(OutboundMessage message, Duration timeout) {
        messagePort().send(message);
        OutboundMessage recorded = sent.peekLast();
        return new Delivered(recorded.channel(), recorded.key(), recorded.payload(), recorded.headers());
    }

    @Override
    protected int transportMessageCount() {
        return sent.size();
    }

    @Override
    protected boolean supportsDelayedDelivery() {
        return false;
    }
}
