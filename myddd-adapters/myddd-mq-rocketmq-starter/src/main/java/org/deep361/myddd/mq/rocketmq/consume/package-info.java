/**
 * 消费侧：按消费组创建厂商 push consumer，把投递交给壳的 {@code InboundDispatcher}，
 * 失败按厂商重投语义（{@code RECONSUME_LATER}）处理。
 *
 * <p>{@link org.deep361.myddd.mq.rocketmq.consume.RocketMqConsumerManager} 是壳的
 * {@code MessageConsumptionPlan} 在本仓的第一位调用方：它只在"有通道路由到 RocketMQ"时建消费者，
 * 一个组一个实例；{@link org.deep361.myddd.mq.rocketmq.consume.RocketMqPushConsumerAdapter}
 * 是厂商接线的测试缝，线上格式的读写单点在
 * {@link org.deep361.myddd.mq.rocketmq.RocketMqWireFormat}。
 * 路由、开关与出站头策略都在壳里，本包一行不重做。
 */
package org.deep361.myddd.mq.rocketmq.consume;
