/**
 * Kafka provider 的根包：本包是 R2 的允许侧——Kafka 厂商类只准出现在本模块，壳里一个都没有。
 *
 * <p>{@link org.deep361.myddd.mq.kafka.KafkaWireFormat} 是线上格式的读写单点：
 * 出站映射（produce 子包）与入站映射（Task 38 的监听接线）共用这一套"名字不改写、值按 UTF-8"的规则。
 */
package org.deep361.myddd.mq.kafka;
