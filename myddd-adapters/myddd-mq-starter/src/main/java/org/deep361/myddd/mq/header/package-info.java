/**
 * 出站头整形：{@link org.deep361.myddd.mq.header.OutboundMessageEnricher}——写 {@code myddd-trace-id} 头，
 * 并拒绝厂商承载能力不一致的空白头。
 *
 * <p>调用方是 provider 实现（交给厂商客户端之前），<b>不是</b>路由层：契约测试直接驱动 provider，
 * 注入点放路由层会让"单独验收一个 provider"必然漏检 traceId 头（MQ-C5）。
 *
 * <p>空白头在这里拒绝而不是留给厂商：RocketMQ 的 {@code putUserProperty} 对空白名/空白值抛异常，
 * Kafka 却允许空字节头——留给厂商层就变成"同一消息一投一抛"，等价性测试无解。
 */
package org.deep361.myddd.mq.header;
