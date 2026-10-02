package org.deep361.myddd.mq.kafka.config;

import org.deep361.myddd.mq.ProviderMessagePort;
import org.deep361.myddd.mq.header.OutboundMessageEnricher;
import org.deep361.myddd.mq.kafka.consume.KafkaConsumerManager;
import org.deep361.myddd.mq.kafka.produce.KafkaMessagePort;
import org.deep361.myddd.mq.listener.InboundDispatcher;
import org.deep361.myddd.mq.route.MessageConsumptionPlan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;

/**
 * Kafka provider 的装配：一个 bean，就是端口本身。
 *
 * <p><b>本模块不新增任何配置键</b>（R5）：连接、序列化、超时、重试全部是 {@code spring.kafka.*} 的事，
 * Boot 的 {@code org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration} 已经把
 * {@code KafkaTemplate<?, ?>} 装配好了（实测方法签名见 {@code bootkafka.txt}）。再造一层
 * {@code myddd.mq.kafka.*} 就是把厂商参数表翻译成框架键（§0.3-5 禁止），而翻译层一定漂移。
 *
 * <p><b>没有 {@code @EnableConfigurationProperties}，因此也没有 {@code @PostConstruct} 校验</b>：
 * Task 35 需要一个 properties 类是因为 {@code myddd.mq.rocketmq.name-server} 是框架自有键、
 * 必须有人在必填缺失时给出人话。这里没有框架自有键，缺失的连接配置由 Boot 与厂商负责报错。
 *
 * <p><b>与 Task 35 同形的三个决定</b>：① 开关用壳的 {@code myddd.mq.enabled}，不自建 provider 级开关；
 * ② 不写 {@code @ConditionalOnClass(KafkaTemplate.class)}（{@code AutoConfiguration.imports} 只有
 * 引入本 jar 才会被读到，条件恒真）；③ 不写 {@code @AutoConfiguration(after = KafkaAutoConfiguration.class)}
 * ——模板是<b>方法参数</b>，在 bean 实例化期按类型解析，与配置类处理顺序无关（写 after 是一条会让人误读的假约束）。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "myddd.mq", name = "enabled", matchIfMissing = true)
public class MydddKafkaAutoConfiguration {

    /**
     * @param kafkaTemplate Boot 装配的模板。参数声明成通配符而不是 {@code KafkaOperations<String,String>}，
     *                      是为了兼容"用户自己贡献一个 {@code KafkaTemplate<Object,Object>}"的部署：
     *                      实测 {@code KafkaTemplate<?, ?>} 形状的 bean 四种参数都能注（{@code k6out.txt} 四行全 OK），
     *                      而反过来把用户那份 {@code <Object,Object>} 注进要求 {@code <String,String>} 的参数时
     *                      解析直接失败——一手实测的原文是 {@code NoSuchBeanDefinitionException: No qualifying
     *                      bean of type 'org.springframework.kafka.core.KafkaOperations<java.lang.String,
     *                      java.lang.String>' available}（{@code k9out2.txt} 第一行，其余四种形状全 OK）。
     *                      那句报错里既没有 {@code myddd.mq} 也没有本模块的类名，比一次受控强转难懂十倍。
     * @return 端口。方法<b>不</b>挂 {@code @ConditionalOnMissingBean}：Task 33 的 {@code AC4}
     *         钉的是"同一 provider 两个端口 ⇒ 启动失败"，这里开替换口子会让那条规则变成空话。
     */
    @Bean
    public ProviderMessagePort mydddKafkaMessagePort(KafkaOperations<?, ?> kafkaTemplate,
                                                     OutboundMessageEnricher enricher) {
        return new KafkaMessagePort(stringTemplate(kafkaTemplate), enricher);
    }

    /**
     * 消费侧生命周期。条件用壳的键 {@code myddd.mq.listener.enabled}，不是 {@code @ConditionalOnBean}。
     *
     * <p>这条条件不是锦上添花：{@code myddd.mq.listener.enabled=false} 时壳根本不产出
     * {@link MessageConsumptionPlan} 与 {@link InboundDispatcher}，本 bean 的方法参数解析会直接失败——
     * "只发不收"的部署就起不来。读同一个开关才能让两侧一致（AC8 钉）。也不用 {@code @ConditionalOnBean}：
     * 它依赖自动配置的处理顺序，而本模块与壳之间在 bean 实例化期按类型解析，与配置类顺序无关。
     *
     * <p><b>不加 {@code @ConditionalOnMissingBean}</b>：与同类的 {@code mydddKafkaMessagePort} 一致，
     * 由 Task 33 的 {@code AC4}（同一 provider 两个端口 ⇒ 启动失败）定下的纪律。要换掉整个消费生命周期请
     * 走更窄的口子——贡献一个自己的 {@link KafkaListenerContainerFactory} bean（AC11 钉那个口子实测有效），
     * 而不是注册第二个 manager 让两套容器抢同一批分区。
     *
     * <p><b>两个工厂参数的形状是最宽的两种</b>（{@code KafkaListenerContainerFactory<?>} 与
     * {@code ConsumerFactory<?, ?>}）：实测 Boot 那两个 bean 都能注进来，且这里<b>没有</b> Task 37 那一次
     * {@code @SuppressWarnings("unchecked")}——容器面 {@code MessageListenerContainer} 不是泛型类型，
     * {@code setupMessageListener} 收 {@code Object}，泛型在本任务的调用链上根本不参与。
     *
     * <p><b>参数名 {@code containerFactory} 不许改成 {@code kafkaListenerContainerFactory}</b>。本仓根 POM
     * 开了 {@code <parameters>true</parameters>}，形参名运行期可见，于是 Spring 在多个同类型候选之间会按参数名
     * 兜底：写成 Boot 的那个 bean 名，就等于框架替用户做了隐式选择——用户给自己工厂标的 {@code @Primary} 会被挤掉
     * （实测：同名 bean ⇒ Boot 退让、异名 + {@code @Primary} ⇒ 拿到 primary 那个、异名且无 primary ⇒ 启动失败
     * {@code expected single matching bean but found 2}）。定制入口因此只有两条，且都由用户显式选：AC11 与 AC12。
     */
    @Bean
    @ConditionalOnProperty(prefix = "myddd.mq.listener", name = "enabled", matchIfMissing = true)
    public KafkaConsumerManager mydddKafkaConsumerManager(KafkaListenerContainerFactory<?> containerFactory,
                                                          ConsumerFactory<?, ?> consumerFactory,
                                                          MessageConsumptionPlan plan,
                                                          InboundDispatcher dispatcher) {
        return new KafkaConsumerManager(containerFactory, consumerFactory, plan, dispatcher);
    }

    /**
     * 唯一的 unchecked 转换点。
     *
     * <p>安全性由两头夹住：框架侧只往模板写 {@code String} 载荷与 {@code String} 键（{@code OutboundMessage}
     * 的两个字段都是 {@code String}）；Boot 缺省把 {@code key.serializer}/{@code value.serializer} 配成
     * {@code StringSerializer}（实测 {@code KafkaProperties} 的 {@code buildProperties()} 只产出这两个键，
     * 值就是 {@code StringSerializer}，{@code k5out.txt}）。
     * 若部署方把序列化器换成别的（例如 {@code ByteArraySerializer}），失败会发生在厂商序列化阶段并
     * 沿 {@link KafkaMessagePort} 的两条失败路之一回到调用方——{@code FT9} 用 {@code ClassCastException}
     * 钉的就是这条路的翻译形态，不留静默。
     */
    @SuppressWarnings("unchecked")
    private static KafkaOperations<String, String> stringTemplate(KafkaOperations<?, ?> template) {
        return (KafkaOperations<String, String>) template;
    }
}
