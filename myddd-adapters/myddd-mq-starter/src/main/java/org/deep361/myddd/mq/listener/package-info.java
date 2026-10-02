/**
 * 消费侧装配：{@link org.deep361.myddd.mq.listener.MessageListenerRegistrar} 把 {@code @MessageListener} 方法
 * 冻结成有序快照，{@link org.deep361.myddd.mq.listener.InboundDispatcher} 按快照把一条
 * {@link org.deep361.myddd.kernel.port.message.InboundMessage} 投给该通道的全部监听方法。
 *
 * <p>这里是 provider 唯一的消费接线点：适配器只负责"把厂商消息翻译成 InboundMessage 后调用 dispatch"，
 * ack 还是重投由适配器按本包返回/抛出的结果决定（R2：壳里没有厂商类，也没有厂商生命周期）。
 *
 * <p>扫描形状与 {@code DomainEventListenerRegistrar} 逐条对齐（{@code SmartInitializingSingleton} + 冻结快照 +
 * 启动期校验 + 签名去重"最具体优先"）：同一框架里两处注解扫描必须是一条约定，不是两套。
 */
package org.deep361.myddd.mq.listener;
