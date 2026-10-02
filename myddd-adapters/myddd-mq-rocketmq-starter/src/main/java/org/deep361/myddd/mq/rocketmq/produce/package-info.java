/**
 * 生产侧：线上格式的唯一产地。
 *
 * <p>{@link org.deep361.myddd.mq.rocketmq.produce.RocketMqMessagePort} 把 {@code OutboundMessage}
 * 翻译成厂商 {@code Message} 并完成失败翻译；
 * {@link org.deep361.myddd.mq.rocketmq.produce.DefaultRocketMqProducer} 是厂商 producer 的
 * 配置与惰性启动宿主。本包是本模块唯一持有厂商发送逻辑的地方。
 */
package org.deep361.myddd.mq.rocketmq.produce;
