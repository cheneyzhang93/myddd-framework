/**
 * 载荷编解码：{@link org.deep361.myddd.mq.payload.MessagePayloadConverter}——业务载荷与 JSON 文本之间的唯一出口。
 *
 * <p>缺省 Mapper 直接取 domain 侧的 {@code JacksonOutboxPayloadSerializer.mapperBuilder()}，因为 Outbox 写出的
 * payload 与这里读入的是同一份线上数据；本模块不再抄第二份约定（第三份一律禁止）。
 *
 * <p>转换失败抛 {@code FrameworkException} 让整条消息消费失败，由 provider 的重试策略接管——
 * 静默跳过一条坏消息等于丢弃数据。
 */
package org.deep361.myddd.mq.payload;
