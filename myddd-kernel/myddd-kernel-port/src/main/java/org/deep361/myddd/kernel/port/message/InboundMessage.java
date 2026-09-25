package org.deep361.myddd.kernel.port.message;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 入站消息：消费端（{@code @MessageListener} 标注的方法）收到的载荷载体。
 *
 * <p>{@code messageId} 为厂商消息 id，可为 {@code null}（不同厂商语义不同：RocketMQ 为 msgId，Kafka 为
 * topic-partition-offset）；消费端做幂等应优先使用业务事件 id（{@link MessageHeaderNames#EVENT_ID}），
 * 而不是 {@code messageId}。
 *
 * @param channel   来源通道
 * @param payload   消息体
 * @param messageId 厂商消息 id，可为 {@code null}
 * @param headers   消息头，可为 {@code null}（等价于空）；内部防御性拷贝，键与值均不可为 {@code null}
 */
public record InboundMessage(String channel, String payload, String messageId, Map<String, String> headers) {

    public InboundMessage {
        Objects.requireNonNull(channel, "channel 不能为 null");
        if (channel.isBlank()) {
            throw new IllegalArgumentException("channel 不能为空白");
        }
        Objects.requireNonNull(payload, "payload 不能为 null");
        if (headers == null) {
            headers = Map.of();
        } else {
            headers.forEach((name, value) -> {
                Objects.requireNonNull(name, "headers 的键不能为 null");
                Objects.requireNonNull(value, "headers 的值不能为 null");
            });
            headers = Map.copyOf(headers);
        }
    }

    /** 按名读取消息头；不存在返回 {@link Optional#empty()}。 */
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(Objects.requireNonNull(name, "name 不能为 null")));
    }

    /** 链路追踪 id（取自 {@link MessageHeaderNames#TRACE_ID} 头）。 */
    public Optional<String> traceId() {
        return header(MessageHeaderNames.TRACE_ID);
    }
}
