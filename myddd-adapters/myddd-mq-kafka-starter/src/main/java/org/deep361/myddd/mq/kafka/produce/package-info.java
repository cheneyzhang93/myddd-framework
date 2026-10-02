/**
 * 生产侧：Kafka 生产端口。
 *
 * <p>{@link org.deep361.myddd.mq.kafka.produce.KafkaMessagePort} 把 {@code OutboundMessage} 经
 * {@code KafkaWireFormat} 写成厂商 {@code ProducerRecord}，交给 Boot 装配的模板并等到终态；
 * 两条失败路（send() 同步抛与 future 晚到）都在这里翻译成人话。本包不碰 producer 生命周期。
 */
package org.deep361.myddd.mq.kafka.produce;
