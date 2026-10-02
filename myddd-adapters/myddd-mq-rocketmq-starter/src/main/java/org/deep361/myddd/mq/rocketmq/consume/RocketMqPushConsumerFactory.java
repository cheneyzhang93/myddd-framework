package org.deep361.myddd.mq.rocketmq.consume;

/**
 * 按消费组产出消费者的工厂：一个组一个实例是厂商的硬约束（同一 JVM 内同组重复注册会被拒），
 * 而 {@code @MessageListener} 允许多个通道共用一个组，所以"每组一个"必须由工厂承担。
 *
 * <p>业务要换掉厂商接线（例如注入带 {@code RPCHook} 的 ACL 版本）时贡献一个本接口的 bean 即可，
 * 不必替换整个消费者生命周期（AC11 钉这个口子）。
 */
@FunctionalInterface
public interface RocketMqPushConsumerFactory {

    /**
     * @param nameServer    {@code myddd.mq.rocketmq.name-server} 的值（已校验非空白）
     * @param consumerGroup 消费组名（注解的 group，或为空时的缺省组）
     */
    RocketMqPushConsumerAdapter create(String nameServer, String consumerGroup);

    /** 缺省工厂：真厂商消费者。 */
    static RocketMqPushConsumerFactory defaultFactory() {
        return DefaultRocketMqPushConsumer::new;
    }
}
