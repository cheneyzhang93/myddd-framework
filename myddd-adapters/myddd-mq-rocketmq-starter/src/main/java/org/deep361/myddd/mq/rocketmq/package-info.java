/**
 * RocketMQ provider 的根包：本包是 R2 的允许侧——厂商类只准出现在 provider 模块，壳里一个都没有。
 *
 * <p>唯一的测试缝 {@link org.deep361.myddd.mq.rocketmq.RocketMqProducer} 住在这里：
 * 模块其余部分对厂商的每次触碰都必须穿过它。
 */
package org.deep361.myddd.mq.rocketmq;
