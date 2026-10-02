package org.deep361.myddd.mq.rocketmq.config;

import org.deep361.myddd.mq.ProviderMessagePort;
import org.deep361.myddd.mq.header.OutboundMessageEnricher;
import org.deep361.myddd.mq.listener.InboundDispatcher;
import org.deep361.myddd.mq.rocketmq.RocketMqProducer;
import org.deep361.myddd.mq.rocketmq.consume.RocketMqConsumerManager;
import org.deep361.myddd.mq.rocketmq.consume.RocketMqPushConsumerFactory;
import org.deep361.myddd.mq.rocketmq.produce.DefaultRocketMqProducer;
import org.deep361.myddd.mq.rocketmq.produce.RocketMqMessagePort;
import org.deep361.myddd.mq.route.MessageConsumptionPlan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * RocketMQ provider 的唯一自动配置（§0.3-6：一个能力一份，provider 模块各一份互不相干）。
 *
 * <p>三个决定，逐个说清：
 * ① <b>开关用壳的 {@code myddd.mq.enabled}，不自建 {@code myddd.mq.rocketmq.enabled}</b>：
 * §1.2.1 没给本模块总开关，而"关掉整条 MQ 能力"的语义必须覆盖 provider——否则关掉的只是路由，
 * 容器里仍留着一个会引导 netty 的生产者，用户以为没有 MQ 了。
 * ② <b>不写 {@code @ConditionalOnClass(DefaultMQProducer.class)}</b>：本模块的
 * {@code AutoConfiguration.imports} 只有在这个 jar 被引入时才会被读到，条件恒真，写它只会让人
 * 以为存在"壳里带 provider 配置但缺厂商类"的形态（那是 Task 33 的壳，不是这里）。
 * ③ <b>不写 {@code @AutoConfiguration(after = MydddMqAutoConfiguration.class)}</b>：
 * 本配置需要的 {@link OutboundMessageEnricher} 是<b>方法参数</b>，壳的 {@code RoutingMessagePort}
 * 需要的 {@code List<ProviderMessagePort>} 是<b>集合注入</b>，两者都在 bean 实例化期解析，
 * 与配置类的处理顺序无关。顺序在这里没有语义，写出来是一条会让人误读的假约束。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "myddd.mq", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(MydddRocketMqProperties.class)
public class MydddRocketMqAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RocketMqProducer mydddRocketMqProducer(MydddRocketMqProperties properties) {
        return DefaultRocketMqProducer.of(properties.getNameServer(), properties.getProducerGroup());
    }

    @Bean
    public ProviderMessagePort mydddRocketMqMessagePort(RocketMqProducer producer, OutboundMessageEnricher enricher) {
        return new RocketMqMessagePort(producer, enricher);
    }

    @Bean
    @ConditionalOnMissingBean
    public RocketMqPushConsumerFactory mydddRocketMqPushConsumerFactory() {
        return RocketMqPushConsumerFactory.defaultFactory();
    }

    /**
     * 消费侧生命周期。条件用壳的键 {@code myddd.mq.listener.enabled}，不是 {@code @ConditionalOnBean}。
     *
     * <p>这条条件不是锦上添花：{@code myddd.mq.listener.enabled=false} 时壳根本不产出
     * {@link MessageConsumptionPlan} 与 {@link InboundDispatcher}，本 bean 的方法参数解析会直接失败——
     * "只发不收"的部署就起不来。读同一个开关才能让两侧一致（AC8 钉）。
     * 也不用 {@code @ConditionalOnBean}：它依赖自动配置的处理顺序，而 Task 35 已经论证过
     * "本模块与壳之间在 bean 实例化期解析，与配置类顺序无关"，混用两种机制只会让人以为顺序有意义。
     */
    @Bean
    @ConditionalOnProperty(prefix = "myddd.mq.listener", name = "enabled", matchIfMissing = true)
    public RocketMqConsumerManager mydddRocketMqConsumerManager(MydddRocketMqProperties properties,
                                                                MessageConsumptionPlan plan,
                                                                InboundDispatcher dispatcher,
                                                                RocketMqPushConsumerFactory consumerFactory) {
        return new RocketMqConsumerManager(properties, plan, dispatcher, consumerFactory);
    }
}
