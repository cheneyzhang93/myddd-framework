package org.deep361.myddd.kernel.port.message;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 出站消息：{@link MessagePort#send(OutboundMessage)} 的载荷载体。
 *
 * <p>载荷是 {@code String}（通常为 JSON）——框架不感知序列化格式，序列化由调用方完成
 * （如 Outbox 投递器使用 {@code OutboxPayloadSerializer}）。
 *
 * @param channel   目标通道（RocketMQ topic / Kafka topic），不可为空白
 * @param payload   消息体，不可为 {@code null}
 * @param key       分区/顺序键，可为 {@code null}；相同 key 的消息在同一分区（Kafka）或同一队列（RocketMQ）内有序
 * @param headers   消息头，可为 {@code null}（等价于空）；内部防御性拷贝，键与值均不可为 {@code null}
 * @param deliverAt 期望投递时间，可为 {@code null}（立即投递）；不支持延迟投递的适配器立即投递，由消费端幂等兜底
 */
public record OutboundMessage(String channel, String payload, String key,
                              Map<String, String> headers, Instant deliverAt) {

    public OutboundMessage {
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

    /** 无 key、无头、立即投递的消息。 */
    public static OutboundMessage of(String channel, String payload) {
        return new OutboundMessage(channel, payload, null, Map.of(), null);
    }

    /** 带顺序键、无头、立即投递的消息。 */
    public static OutboundMessage of(String channel, String payload, String key) {
        return new OutboundMessage(channel, payload, key, Map.of(), null);
    }

    /** 按名读取消息头；不存在返回 {@link Optional#empty()}。 */
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(Objects.requireNonNull(name, "name 不能为 null")));
    }
}
