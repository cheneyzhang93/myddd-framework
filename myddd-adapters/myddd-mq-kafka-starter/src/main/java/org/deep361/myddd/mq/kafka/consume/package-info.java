/**
 * Kafka 消费侧：按消费组创建厂商监听容器，把投递交给壳的 InboundDispatcher，
 * 失败一律外抛给厂商的错误处理链（框架不接管提交、不接管重投节奏）。
 */
package org.deep361.myddd.mq.kafka.consume;
