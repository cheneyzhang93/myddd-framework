package org.deep361.myddd.mq.kafka.config;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.common.trace.TraceContext;
import org.deep361.myddd.kernel.port.message.InboundMessage;
import org.deep361.myddd.kernel.port.message.MessageHeaderNames;
import org.deep361.myddd.kernel.port.message.MessageListener;
import org.deep361.myddd.kernel.port.message.MessagePort;
import org.deep361.myddd.kernel.port.message.OutboundMessage;
import org.deep361.myddd.mq.MqProvider;
import org.deep361.myddd.mq.ProviderMessagePort;
import org.deep361.myddd.mq.config.MydddMqAutoConfiguration;
import org.deep361.myddd.mq.header.OutboundMessageEnricher;
import org.deep361.myddd.mq.kafka.KafkaWireFormat;
import org.deep361.myddd.mq.kafka.consume.ConsumptionRig;
import org.deep361.myddd.mq.kafka.consume.KafkaConsumerManager;
import org.deep361.myddd.mq.kafka.consume.RecordingKafkaContainerFactory;
import org.deep361.myddd.mq.kafka.consume.RecordingKafkaMessageListenerContainer;
import org.deep361.myddd.mq.kafka.produce.KafkaMessagePort;
import org.deep361.myddd.mq.kafka.produce.RecordingKafkaProducerFactory;
import org.deep361.myddd.mq.listener.InboundDispatcher;
import org.deep361.myddd.mq.route.MessageConsumptionPlan;
import org.deep361.myddd.mq.route.RoutingMessagePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kafka provider 的 bean 面：装配产出的形状（恰好一个 bean）、壳的两个开关如何落到端口上、
 * 以及"用的确实是 Boot 装配的那份模板"这条端到端证据。
 *
 * <p>夹具一律是录制工厂 + 真模板，所以本类除 AC2（必须发一条才看得到头）之外是零 send、零 producer、
 * 零网络；实测模板把 {@code createProducer()} 推迟到首次 send（{@code k3out.txt}），
 * 因此"上下文起得来"本身不构成任何网络连接。
 */
class MydddKafkaAutoConfigurationTest {

    /** 壳 + 本模块：AC2 到 AC6 被测的正是"壳的开关与路由能不能落到本模块的端口上"。 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddMqAutoConfiguration.class,
                    MydddKafkaAutoConfiguration.class));

    @AfterEach
    void clearTraceContext() {
        TraceContext.clear();
    }

    @Test
    void should_contribute_exactly_the_port_and_no_property_holder() {
        // R5 的账必须可执行：本模块只贡献两个 bean 定义（端口 + 消费者 manager），且没有任何配置键持有者。
        // 基线与加入模块的两次运行共用同一份用户配置，差值里因此只剩本模块的贡献；
        // 壳不在这里出现，因为壳在"零 provider"时会启动失败，那份失败会把差值读成 0。
        AtomicReference<String[]> baseline = new AtomicReference<>();
        new ApplicationContextRunner()
                .withUserConfiguration(PortDependenciesConfiguration.class)
                .run(context -> baseline.set(context.getBeanDefinitionNames()));
        AtomicReference<Set<String>> contributed = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MydddKafkaAutoConfiguration.class))
                .withUserConfiguration(PortDependenciesConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // 第二个 bean 的名字与类型都要对上（AC1 的差值期望从 1 改 2）。
                    assertThat(context.getBean("mydddKafkaConsumerManager")).isInstanceOf(KafkaConsumerManager.class);
                    Set<String> names = new HashSet<>(Arrays.asList(context.getBeanDefinitionNames()));
                    names.removeAll(Arrays.asList(baseline.get()));
                    names.removeIf(name -> isConfigurationSelfRegistration(context, name));
                    contributed.set(names);
                });
        assertThat(contributed.get()).containsExactlyInAnyOrder(
                "mydddKafkaMessagePort", "mydddKafkaConsumerManager");
        assertThat(MydddKafkaAutoConfiguration.class.getAnnotation(EnableConfigurationProperties.class)).isNull();
        assertThat(beanClassesAnnotatedWithConfigurationProperties()).isEmpty();
    }

    @Test
    void should_use_the_container_enricher_bean() {
        // myddd.mq.trace-header.enabled 只有接到 provider 上才算生效：端口注入的必须是容器里那份 enricher。
        // 两条断言是一对：头里确实没有 traceId（可观察结果），字段里确实是同一个实例——
        // 只断前一条时，"端口自己 new 一个 false 的 enricher"这种写法照样能过。
        TraceContext.set("ac2-trace");
        runner.withUserConfiguration(TemplateConfiguration.class)
                .withPropertyValues("myddd.mq.trace-header.enabled=false", "myddd.mq.listener.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ProviderMessagePort port = context.getBean(ProviderMessagePort.class);
                    assertThat(readField(port, "enricher")).isSameAs(context.getBean(OutboundMessageEnricher.class));
                    port.send(OutboundMessage.of("order-events", "p"));
                    Map<String, String> headers = KafkaWireFormat.userHeaders(lastRecord(context).headers());
                    assertThat(headers).doesNotContainKey(MessageHeaderNames.TRACE_ID);
                });
    }

    @Test
    void should_produce_a_port_that_answers_kafka() {
        // 与 PP1/PP2 重复是有意的：那里测对象，这里测"装配出来的那个对象"。
        runner.withUserConfiguration(TemplateConfiguration.class)
                .withPropertyValues("myddd.mq.listener.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            // 壳靠 provider() 收集端口，"唯一"本身就是契约（同一 provider 两份实现会启动失败）。
            assertThat(context.getBeansOfType(ProviderMessagePort.class)).hasSize(1);
            ProviderMessagePort port = context.getBean(ProviderMessagePort.class);
            assertThat(port).isInstanceOf(KafkaMessagePort.class);
            assertThat(port.provider()).isEqualTo(MqProvider.KAFKA);
            assertThat(port.supportsDelayedDelivery()).isFalse();
            assertThat(context.getBean(MessagePort.class)).isInstanceOf(RoutingMessagePort.class);
        });
    }

    @Test
    void should_produce_nothing_when_mq_is_disabled() {
        // 总开关优先于任何装配；本模块没有必填校验，所以不存在"关掉反而炸"的分支。
        // 一条 spring.kafka.* 都不给，Boot 的 kafka 自动配置也不在这里：模板缺失不该成为启动障碍。
        runner.withPropertyValues("myddd.mq.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ProviderMessagePort.class);
            assertThat(context).doesNotHaveBean(MessagePort.class);
            assertThat(context).doesNotHaveBean(OutboundMessageEnricher.class);
        });
    }

    @Test
    void should_wire_onto_boots_real_kafka_autoconfiguration() {
        // "复用 Boot 的装配、零新增键"（R5）唯一的端到端证据：容器里那份模板就是端口内部用的那一份。
        // 全程零 send，因此零 producer、零网络；"工厂内部何时建 producer"归 Task 40 的容器 IT。
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration.class,
                        MydddMqAutoConfiguration.class, MydddKafkaAutoConfiguration.class))
                .withPropertyValues("spring.kafka.bootstrap-servers=localhost:1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(KafkaTemplate.class);
                    assertThat(context).hasSingleBean(DefaultKafkaProducerFactory.class);
                    ProviderMessagePort port = context.getBean(ProviderMessagePort.class);
                    assertThat(readField(port, "template")).isSameAs(context.getBean(KafkaOperations.class));
                });
    }

    @Test
    void should_honor_a_user_declared_template_of_another_generic_shape() {
        // Step 5 把参数写成 KafkaOperations<?,?> 的自动化版本（实测 k9out2.txt：通配符与 <Object,Object>
        // 等四种形状全 OK，而要求 <String,String> 的参数会撞
        // NoSuchBeanDefinitionException: No qualifying bean of type
        // 'org.springframework.kafka.core.KafkaOperations<java.lang.String, java.lang.String>' available）。
        // 不加"要求 <String,String> 就失败"的反例用例：那是 Spring 的行为，不是本模块的交付面。
        runner.withUserConfiguration(ObjectTemplateConfiguration.class)
                .withPropertyValues("myddd.mq.listener.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(KafkaOperations.class);
            ProviderMessagePort port = context.getBean(ProviderMessagePort.class);
            assertThat(readField(port, "template")).isSameAs(context.getBean(KafkaTemplate.class));
        });
    }

    /** 录制工厂 + 由它构造的真模板：AC 组不校验线上格式，只需要"一次发送能被读回来"。 */
    @Configuration(proxyBeanMethods = false)
    static class TemplateConfiguration {

        @Bean
        RecordingKafkaProducerFactory mydddKafkaRecordingFactory() {
            return new RecordingKafkaProducerFactory();
        }

        @Bean
        KafkaTemplate<String, String> mydddKafkaRecordingTemplate(RecordingKafkaProducerFactory factory) {
            return RecordingKafkaProducerFactory.newTemplate(factory);
        }
    }

    /**
     * 端口 + manager 的全部外部依赖：AC1 用它们把壳与 Boot 都排除掉，差值里因此只剩本模块的两个 bean。
     *
     * <p><b>{@code RIG} 用 {@code rocketMqOnly()} 是有意的</b>：manager 一被实例化就会被 Spring 生命周期回调
     * {@code start()}（{@code isAutoStartup()} 实测为 true），而这里给出的 {@code plan} 让
     * {@code subscriptionsFor(KAFKA)} 返回空 map，于是 {@code startGroup} 一次都不被调用——
     * 零容器、零网络。工厂因此没有第二次被记录，与 {@code CM1} 同源。
     *
     * <p><b>不注册 {@code MessageListenerRegistrar} bean</b>：它实现 {@code SmartInitializingSingleton}，
     * 一被注册成 bean 就会被 runner 的容器回调 {@code afterSingletonsInstantiated()}，把 {@code listeners}
     * 快照按 runner 的空通道集覆盖掉，进而让 rig 的 plan 读出空订阅——这与"用 rig 里已冻结的注册表"意图相反。
     * {@code InboundDispatcher} 与 {@code MessageConsumptionPlan} 已经持有 rig 的 registrar 引用，
     * 不需要它作为 bean 出现。
     */
    @Configuration(proxyBeanMethods = false)
    static class PortDependenciesConfiguration {

        static final ConsumptionRig RIG = ConsumptionRig.rocketMqOnly();

        @Bean
        RecordingKafkaProducerFactory mydddKafkaRecordingFactory() {
            return new RecordingKafkaProducerFactory();
        }

        @Bean
        KafkaTemplate<String, String> mydddKafkaRecordingTemplate(RecordingKafkaProducerFactory factory) {
            return RecordingKafkaProducerFactory.newTemplate(factory);
        }

        @Bean
        OutboundMessageEnricher mydddKafkaTestEnricher() {
            return new OutboundMessageEnricher();
        }

        @Bean
        RecordingKafkaContainerFactory mydddKafkaRecordingContainerFactory() {
            return new RecordingKafkaContainerFactory();
        }

        @Bean
        ConsumerFactory<Object, Object> mydddKafkaCountingConsumerFactory() {
            return RIG.consumerFactory;
        }

        @Bean
        MessageConsumptionPlan mydddKafkaTestPlan() {
            return RIG.plan;
        }

        @Bean
        InboundDispatcher mydddKafkaTestDispatcher() {
            return RIG.dispatcher;
        }
    }

    /** 用户自己声明的模板，泛型形状与端口的 String 载荷不同：这里唯一的变量是 Spring 的类型解析。 */
    @Configuration(proxyBeanMethods = false)
    static class ObjectTemplateConfiguration {

        /** 自带录制工厂：本条只注册这一个配置类，容器里必须同时有模板要的协作者与"唯一一份模板"。 */
        @Bean
        RecordingKafkaProducerFactory mydddKafkaRecordingFactory() {
            return new RecordingKafkaProducerFactory();
        }

        @Bean
        @SuppressWarnings({"unchecked", "rawtypes"})
        KafkaTemplate<Object, Object> mydddKafkaUserTemplate(RecordingKafkaProducerFactory factory) {
            // 录制工厂只声明了 <String,String>，而本条用例一次都不发送，运行时形状无关；
            // 有意义的是这个 bean 的**声明类型**是 KafkaTemplate<Object,Object>。
            return new KafkaTemplate<>((ProducerFactory) factory, false);
        }
    }

    /**
     * runner 会把自动配置类自己也注册成一条 bean 定义，直接算差值会得到 2。
     * 判据取"没有工厂方法的定义 + beanClassName 就是配置类本身"，不依赖注册时用的 bean 名怎么写。
     */
    private static boolean isConfigurationSelfRegistration(ConfigurableApplicationContext context, String beanName) {
        // 走 getBeanFactory()：runner 交出的上下文是代理，它自己不是 BeanDefinitionRegistry。
        BeanDefinition definition = ((BeanDefinitionRegistry) context.getBeanFactory()).getBeanDefinition(beanName);
        return definition.getFactoryMethodName() == null
                && MydddKafkaAutoConfiguration.class.getName().equals(definition.getBeanClassName());
    }

    /** "本模块零框架自有键"的第二条账：包内没有任何 {@code @ConfigurationProperties} 类。 */
    private static List<String> beanClassesAnnotatedWithConfigurationProperties() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ConfigurationProperties.class));
        List<String> found = new ArrayList<>();
        scanner.findCandidateComponents("org.deep361.myddd.mq.kafka")
                .forEach(candidate -> found.add(candidate.getBeanClassName()));
        return found;
    }

    private static ProducerRecord<String, String> lastRecord(ConfigurableApplicationContext context) {
        return context.getBean(RecordingKafkaProducerFactory.class).last().lastRecord();
    }

    private static Object readField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (NoSuchFieldException | IllegalAccessException failure) {
            throw new AssertionError("端口字段 " + name + " 读不出来（字段改名要同步这里）", failure);
        }
    }

    /**
     * AC7：消费侧工厂的两份"用的是 Boot 装配的那份"证据，与 AC5 的模板钉子合成一对。
     *
     * <p>零 {@code @MessageListener} bean ⇒ 计划返回空 map ⇒ {@code KafkaConsumerManager.start()} 走
     * "一条通道都不属于 Kafka"的 return 分支（{@code CM1} 的语义），<b>真</b>的 Boot 工厂因此一次都不被调用，
     * 没有真容器、没有网络。字段用反射直读，判据是"两个引用 == 容器里那两个 bean"。
     */
    @Test
    void should_have_the_consumer_manager_on_boots_real_factories() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        KafkaAutoConfiguration.class,
                        MydddMqAutoConfiguration.class,
                        MydddKafkaAutoConfiguration.class))
                .withPropertyValues("spring.kafka.bootstrap-servers=localhost:1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(KafkaConsumerManager.class);
                    KafkaConsumerManager manager = context.getBean(KafkaConsumerManager.class);
                    assertThat(readField(manager, "containerFactory"))
                            .isSameAs(context.getBean("kafkaListenerContainerFactory"));
                    assertThat(readField(manager, "consumerFactory"))
                            .isSameAs(context.getBean("kafkaConsumerFactory"));
                    // Boot 那个 factory 是 ConcurrentKafkaListenerContainerFactory——本条钉的是"框架没自建第三种"。
                    assertThat(context.getBean("kafkaListenerContainerFactory"))
                            .isInstanceOf(ConcurrentKafkaListenerContainerFactory.class);
                });
    }

    /** AC8：{@code myddd.mq.listener.enabled=false} 时壳不产出计划/分发器，本模块的 manager 必须同步退让。 */
    @Test
    void should_produce_no_consumer_manager_when_the_listener_switch_is_off() {
        runner.withUserConfiguration(TemplateConfiguration.class, AChannelListenerConfiguration.class)
                .withBean(RecordingKafkaContainerFactory.class, RecordingKafkaContainerFactory::new)
                .withBean(ConsumerFactory.class, () -> new ConsumptionRig.CountingConsumerFactory("g-from-props"))
                .withPropertyValues(
                        "myddd.mq.listener.enabled=false",
                        "spring.kafka.consumer.group-id=g-from-props",
                        "myddd.mq.channels.a-channel.provider=kafka")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(KafkaConsumerManager.class);
                    assertThat(context).doesNotHaveBean(MessageConsumptionPlan.class);
                    assertThat(context).doesNotHaveBean(InboundDispatcher.class);
                    // 工厂 bean 在，但一次都没被建容器 ⇒ "只发不收"部署确实起得来
                    assertThat(context.getBean(RecordingKafkaContainerFactory.class).createCount()).isZero();
                });
    }

    /** AC9：总开关关到本模块消费侧的形态，与 Task 37 的 AC4（发送侧）分工对称。 */
    @Test
    void should_produce_nothing_for_consumers_when_mq_is_disabled() {
        runner.withUserConfiguration(AChannelListenerConfiguration.class)
                .withBean(RecordingKafkaContainerFactory.class, RecordingKafkaContainerFactory::new)
                .withBean(ConsumerFactory.class, () -> new ConsumptionRig.CountingConsumerFactory("g-from-props"))
                .withPropertyValues(
                        "myddd.mq.enabled=false",
                        "spring.kafka.consumer.group-id=g-from-props")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(KafkaConsumerManager.class);
                    assertThat(context).doesNotHaveBean(ProviderMessagePort.class);
                    assertThat(context).doesNotHaveBean(MessagePort.class);
                    assertThat(context.getBean(RecordingKafkaContainerFactory.class).createCount()).isZero();
                });
    }

    /**
     * AC10：消费侧唯一的跨模块端到端钉子——从"刷新"到"业务方法收到 {@code InboundMessage}"整条链跑通。
     *
     * <p>容器工厂用替身（{@code withBean("kafkaListenerContainerFactory", …)} 顶掉 Boot 那份，实测 Boot 退让），
     * 消费者工厂同样顶替，因此<b>零网络</b>。装配跑完之后：
     * <ul>
     *   <li>录制容器的顺序账逐字 {@code [create a-channel, name myddd-kafka-g-from-props, register, start
     *       group=g-from-props]}（{@code CM2} 的账在装配路径上重跑）；</li>
     *   <li>{@code deliver(wire("a-channel",…))} ⇒ 业务方法收到与 {@code WF9} 同值的四项；</li>
     *   <li>同容器再投一条不在注册表里的通道 ⇒ 壳的 {@code InboundDispatcher} 抛 {@link FrameworkException}
     *       ——"没人听的通道不会被静默提交 offset"的落点。</li>
     * </ul>
     */
    @Test
    void should_dispatch_a_delivered_record_end_to_end_through_the_shell() {
        RecordingKafkaContainerFactory replacement = new RecordingKafkaContainerFactory();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        MydddMqAutoConfiguration.class, MydddKafkaAutoConfiguration.class))
                .withUserConfiguration(TemplateConfiguration.class, AChannelListenerConfiguration.class)
                .withBean("kafkaListenerContainerFactory", KafkaListenerContainerFactory.class, () -> replacement)
                .withBean(ConsumerFactory.class, () -> new ConsumptionRig.CountingConsumerFactory("g-from-props"))
                .withPropertyValues(
                        "spring.kafka.consumer.group-id=g-from-props",
                        "myddd.mq.channels.a-channel.provider=kafka")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(replacement.createCount()).isEqualTo(1);
                    RecordingKafkaMessageListenerContainer container = replacement.created().get(0);
                    assertThat(container.calls()).containsExactly(
                            "create a-channel",
                            "name myddd-kafka-g-from-props",
                            "register",
                            "start group=g-from-props");
                    // 端到端投递：业务方法收到的四项与 WF9 逐字一致
                    container.deliver(ConsumptionRig.wire("a-channel", "{\"n\":1}", Map.of("x-a", "1")));
                    AChannelListener bean = context.getBean(AChannelListener.class);
                    assertThat(bean.received).hasSize(1);
                    InboundMessage message = bean.received.get(0);
                    assertThat(message.channel()).isEqualTo("a-channel");
                    assertThat(message.payload()).isEqualTo("{\"n\":1}");
                    assertThat(message.messageId()).isEqualTo("a-channel-0-77");
                    assertThat(message.headers()).containsEntry("x-a", "1");
                    // 第二条投给没监听的通道 ⇒ 壳抛，本模块不吞
                    assertThatThrownBy(() -> container.deliver(
                            ConsumptionRig.wire("nobody-listens", "{}")))
                            .isInstanceOf(FrameworkException.class)
                            .hasMessageContaining("没有任何 @MessageListener");
                });
    }

    /** AC11：用户同名 bean 让 Boot 退让（{@code @ConditionalOnMissingBean} 的实测落点），框架不自建第三种工厂。 */
    @Test
    void should_honor_an_application_provided_container_factory_named_like_boots() {
        RecordingKafkaContainerFactory replacement = new RecordingKafkaContainerFactory();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        KafkaAutoConfiguration.class,
                        MydddMqAutoConfiguration.class,
                        MydddKafkaAutoConfiguration.class))
                .withBean("kafkaListenerContainerFactory", KafkaListenerContainerFactory.class,
                        () -> replacement)
                .withPropertyValues("spring.kafka.bootstrap-servers=localhost:1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(KafkaListenerContainerFactory.class);
                    assertThat(context).doesNotHaveBean(ConcurrentKafkaListenerContainerFactory.class);
                    KafkaConsumerManager manager = context.getBean(KafkaConsumerManager.class);
                    assertThat(readField(manager, "containerFactory")).isSameAs(replacement);
                });
    }

    /**
     * AC12：两个异名工厂 ⇒ 框架不选，启动失败。
     *
     * <p>实测原文（{@code k18out.txt} 第 C 段）："expected single matching bean but found 2:
     * myKafkaFactory,kafkaListenerContainerFactory"，外层 {@code UnsatisfiedDependencyException} 是 Spring 的
     * 包装。这条钉的是"参数名保持 {@code containerFactory} 而不是 Boot 那个名字"这条纪律的落点——一旦改成
     * Boot 的名字，Spring 会按参数名兜底，把用户的 {@code @Primary} 挤掉，隐式选择在装配里做掉。
     */
    @Test
    void should_refuse_to_choose_between_two_container_factories() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        KafkaAutoConfiguration.class,
                        MydddMqAutoConfiguration.class,
                        MydddKafkaAutoConfiguration.class))
                .withBean("myKafkaFactory", KafkaListenerContainerFactory.class,
                        RecordingKafkaContainerFactory::new)
                .withPropertyValues("spring.kafka.bootstrap-servers=localhost:1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable startup = context.getStartupFailure();
                    assertThat(startup).isNotNull();
                    Throwable root = startup;
                    while (root.getCause() != null && !(root instanceof NoUniqueBeanDefinitionException)) {
                        root = root.getCause();
                    }
                    assertThat(root).isInstanceOf(NoUniqueBeanDefinitionException.class);
                    assertThat(root.getMessage()).contains("expected single matching bean but found 2")
                            .contains("myKafkaFactory").contains("kafkaListenerContainerFactory");
                });
    }

    /** AC 组的监听 bean 材料：一条 {@code a-channel}，收到的 {@code InboundMessage} 记在实例字段里。 */
    static class AChannelListener {

        final List<InboundMessage> received = new ArrayList<>();

        @MessageListener(channel = "a-channel")
        public void onMessage(InboundMessage message) {
            received.add(message);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AChannelListenerConfiguration {

        @Bean
        AChannelListener mydddKafkaTestListener() {
            return new AChannelListener();
        }
    }
}
