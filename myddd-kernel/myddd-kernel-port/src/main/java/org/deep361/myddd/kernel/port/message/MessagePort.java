package org.deep361.myddd.kernel.port.message;

/**
 * 消息发送端口：MQ 能力的统一入口，由适配器实现（Plan 4：rocketmq / kafka）。
 *
 * <p><b>实现契约</b>：(a) 发送失败必须抛出异常，不得静默丢弃——可靠投递由上层的 Outbox 投递器负责重试；
 * (b) 实现须线程安全（会被投递器的线程池并发调用）；(c) 不得吞掉序列化/网络异常。
 */
public interface MessagePort {

    /**
     * 发送一条消息。
     *
     * @param message 待发送消息，不可为 {@code null}
     * @throws RuntimeException 发送失败（由调用方决定重试或降级）
     */
    void send(OutboundMessage message);

    /** 便捷重载：向指定通道发送字符串载荷（无 key、无头、立即投递）。 */
    default void send(String channel, String payload) {
        send(OutboundMessage.of(channel, payload));
    }
}
