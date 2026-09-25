package org.deep361.myddd.kernel.port.message;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 消息监听：标注在消费方法上（方法参数为 {@link InboundMessage} 或业务载荷类型），由 MQ 适配器在启动期
 * 扫描并注册（Plan 4）。
 *
 * <p>方法签名约束（适配器启动期校验，违反即启动失败）：恰好一个参数；返回类型为 {@code void}。
 * 方法抛出的异常视为消费失败，由适配器的重试策略处理。
 *
 * <p>通道与实现的路由由配置决定：{@code myddd.mq.channels.<channel>.provider=rocketmq|kafka}。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MessageListener {

    /** 订阅通道（RocketMQ topic / Kafka topic），必填。 */
    String channel();

    /** 消费组；留空表示使用适配器的默认消费组。 */
    String group() default "";

    /** 说明文字（仅用于文档与诊断日志）。 */
    String description() default "";
}
