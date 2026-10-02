package org.deep361.myddd.mq.rocketmq.config;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.Message;
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
import org.deep361.myddd.mq.listener.InboundDispatcher;
import org.deep361.myddd.mq.rocketmq.RocketMqProducer;
import org.deep361.myddd.mq.rocketmq.consume.RecordingRocketMqPushConsumer;
import org.deep361.myddd.mq.rocketmq.consume.RocketMqConsumerManager;
import org.deep361.myddd.mq.rocketmq.consume.RocketMqPushConsumerAdapter;
import org.deep361.myddd.mq.rocketmq.consume.RocketMqPushConsumerFactory;
import org.deep361.myddd.mq.rocketmq.produce.DefaultRocketMqProducer;
import org.deep361.myddd.mq.rocketmq.produce.RecordingRocketMqProducer;
import org.deep361.myddd.mq.rocketmq.produce.RocketMqMessagePort;
import org.deep361.myddd.mq.route.MessageConsumptionPlan;
import org.deep361.myddd.mq.route.RoutingMessagePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 条件装配与跨模块接缝：provider 的 bean 面（壳的 {@code myddd.mq.enabled} 优先于本模块必填校验）、
 * {@code @ConditionalOnMissingBean(RocketMqProducer)} 逃逸口、壳 → 端口 → 厂商线上格式的端到端一条。
 */
class MydddRocketMqAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddMqAutoConfiguration.class,
                    MydddRocketMqAutoConfiguration.class));

    @AfterEach
    void clearTraceContext() {
        TraceContext.clear();
    }

    @Test
    void should_produce_the_producer_and_the_provider_port() {
        runner.withPropertyValues(
                        "myddd.mq.rocketmq.name-server=127.0.0.1:9876",
                        "myddd.mq.rocketmq.producer-group=g-ac1",
                        "myddd.mq.channels.order-events.provider=rocketmq")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RocketMqProducer.class);
                    assertThat(context.getBean(RocketMqProducer.class)).isInstanceOf(DefaultRocketMqProducer.class);
                    // "未启动"由 PR1 钉（of() 只构造）：上下文里有 bean ≠ 引导了 netty。
                    ProviderMessagePort port = context.getBean(ProviderMessagePort.class);
                    assertThat(port.provider()).isEqualTo(MqProvider.ROCKETMQ);
                    assertThat(port.supportsDelayedDelivery()).isTrue();
                });
    }

    @Test
    void should_produce_nothing_when_mq_is_disabled() {
        // 总开关优先于必填校验：不给 name-server 也能起——"关掉 MQ"不许反过来炸。
        runner.withPropertyValues("myddd.mq.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RocketMqProducer.class);
                    assertThat(context).doesNotHaveBean(ProviderMessagePort.class);
                    assertThat(context).doesNotHaveBean(MydddRocketMqProperties.class);
                });
    }

    @Test
    void should_fail_fast_when_the_name_server_is_missing() {
        runner.withPropertyValues("myddd.mq.default-provider=rocketmq")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(FrameworkException.class)
                            .hasMessageContaining("myddd.mq.rocketmq.name-server");
                });
    }

    @Test
    void should_honor_an_application_provided_producer() {
        // 唯一留给用户的口子：换掉厂商接线（全部参数），端口必须用用户的那个而不是自建的。
        RecordingRocketMqProducer fake = new RecordingRocketMqProducer();
        runner.withBean(RocketMqProducer.class, () -> fake)
                .withPropertyValues("myddd.mq.rocketmq.name-server=127.0.0.1:9876",
                        "myddd.mq.default-provider=rocketmq")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RocketMqProducer.class)).isSameAs(fake);
                    assertThat(context).doesNotHaveBean(DefaultRocketMqProducer.class);
                    context.getBean(ProviderMessagePort.class).send(OutboundMessage.of("order-events", "p"));
                    assertThat(fake.sent()).hasSize(1);
                });
    }

    @Test
    void should_route_a_real_send_through_the_shell_into_the_vendor_wire() {
        // 跨模块接缝的唯一端到端钉子：壳路由 + provider 映射一起跑，断言落在真会上飞机的字节上。
        RecordingRocketMqProducer fake = new RecordingRocketMqProducer();
        runner.withBean(RocketMqProducer.class, () -> fake)
                .withPropertyValues(
                        "myddd.mq.rocketmq.name-server=127.0.0.1:9876",
                        "myddd.mq.channels.order-events.provider=rocketmq")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MessagePort port = context.getBean(MessagePort.class);
                    assertThat(port).isInstanceOf(RoutingMessagePort.class);
                    port.send(new OutboundMessage("order-events", "{}", "k", Map.of("x-a", "1"), null));
                    assertThat(fake.sent()).hasSize(1);
                    Message wire = fake.lastWire();
                    assertThat(wire.getTopic()).isEqualTo("order-events");
                    assertThat(wire.getProperties()).containsEntry("KEYS", "k");
                    assertThat(wire.getUserProperty("x-a")).isEqualTo("1");
                });
    }

    @Test
    void should_use_the_container_enricher_bean() {
        // Task 34 的开关只有接到 provider 上才算生效：端口注入的必须是容器里的 enricher，不是自己 new 的。
        TraceContext.set("ac6-trace");
        RecordingRocketMqProducer fake = new RecordingRocketMqProducer();
        runner.withBean(RocketMqProducer.class, () -> fake)
                .withPropertyValues(
                        "myddd.mq.rocketmq.name-server=127.0.0.1:9876",
                        "myddd.mq.default-provider=rocketmq",
                        "myddd.mq.trace-header.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ProviderMessagePort.class)).isInstanceOf(RocketMqMessagePort.class);
                    context.getBean(ProviderMessagePort.class).send(OutboundMessage.of("order-events", "p"));
                    assertThat(fake.sent()).hasSize(1);
                    assertThat(fake.lastWire().getProperties()).doesNotContainKey(MessageHeaderNames.TRACE_ID);
                });
    }

    // ---- AC7–AC11：Task 36 的消费侧 bean 面 ----
    //
    // ⚠️ 这四条都必须给工厂钉上替身：SmartLifecycle.isAutoStartup() 实测 true，上下文刷新就真起消费者。
    // 忘了替身而 name-server 又是假地址时，它会以 1.1–3.2 秒的 connect 失败红在这里，
    // 而不是以"缺 bean"红——两种失败形态不能混着读（详见计划片段 Step 9 的那句提醒）。

    /** AC 组共用的监听 bean：通道名与组名必须是编译期常量才进得了路由表。 */
    public static class OrderPaidListener {

        private final List<InboundMessage> received;

        public OrderPaidListener(List<InboundMessage> received) {
            this.received = received;
        }

        @MessageListener(channel = "order-events", group = "g-ac")
        public void onOrderPaid(InboundMessage message) {
            received.add(message);
        }
    }

    /**
     * AC 组的工厂替身：与 {@code ConsumptionRig.RecordingFactory} 同一形状，但按 AC 的需要只留"建了哪些"。
     *
     * <p>{@code closeOrder} 传一张丢弃用的表：AC 断的是 bean 面与端到端投递，不是跨消费者的关闭顺序
     * （那是 {@code CM9} 的判据，同模块只钉一处）。
     */
    private static final class ConsumerFactoryProbe implements RocketMqPushConsumerFactory {

        private final List<RecordingRocketMqPushConsumer> created = new CopyOnWriteArrayList<>();

        @Override
        public RocketMqPushConsumerAdapter create(String nameServer, String consumerGroup) {
            RecordingRocketMqPushConsumer consumer =
                    new RecordingRocketMqPushConsumer(nameServer, consumerGroup, new CopyOnWriteArrayList<>());
            created.add(consumer);
            return consumer;
        }

        List<RecordingRocketMqPushConsumer> created() {
            return created;
        }

        /** 恰好一个消费者（AC 的 rig 只有一条通道一个组）；数量不对时报出实建清单而不是下标越界。 */
        RecordingRocketMqPushConsumer only() {
            assertThat(created).as("实建消费者：" + created.stream()
                    .map(RecordingRocketMqPushConsumer::consumerGroup).toList()).hasSize(1);
            return created.get(0);
        }
    }

    @Test
    void should_produce_the_consumer_factory_and_the_consumer_manager() {
        // AC7：manager bean 就位，且缺省工厂口子被贡献的替身占住后，manager 真的用它——
        // create 收到的正是配置里的 name-server 与该通道的组。
        ConsumerFactoryProbe factory = new ConsumerFactoryProbe();
        runner.withBean(RocketMqPushConsumerFactory.class, () -> factory)
                .withBean(OrderPaidListener.class, () -> new OrderPaidListener(new CopyOnWriteArrayList<>()))
                .withPropertyValues(
                        "myddd.mq.rocketmq.name-server=127.0.0.1:9876",
                        "myddd.mq.rocketmq.producer-group=g-ac7",
                        "myddd.mq.channels.order-events.provider=rocketmq")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RocketMqConsumerManager.class);
                    assertThat(context).hasSingleBean(RocketMqPushConsumerFactory.class);
                    // 刷新即启动（isAutoStartup 实测 true）：工厂没被调用就等于 manager 是个空壳。
                    RecordingRocketMqPushConsumer consumer = factory.only();
                    assertThat(consumer.nameServer()).isEqualTo("127.0.0.1:9876");
                    assertThat(consumer.consumerGroup()).isEqualTo("g-ac");
                    // consumer.* 的三项缺省值经 manager 落到厂商调用上（单位是分钟，见 CM2）。
                    assertThat(consumer.settings()).isEqualTo(new RecordingRocketMqPushConsumer.Settings(16, 15));
                    assertThat(consumer.subscriptions()).extracting(RecordingRocketMqPushConsumer.Subscription::topic)
                            .containsExactly("order-events");
                    assertThat(context.getBean(RocketMqConsumerManager.class).isRunning()).isTrue();
                });
    }

    @Test
    void should_produce_no_consumer_manager_when_the_listener_switch_is_off() {
        // AC8：两侧读同一个开关。用 @ConditionalOnBean／靠参数解析失败去"实现"关闭，
        // 会让 myddd.mq.listener.enabled=false 的只发不收部署直接起不来。
        runner.withBean(OrderPaidListener.class, () -> new OrderPaidListener(new CopyOnWriteArrayList<>()))
                .withPropertyValues(
                        "myddd.mq.rocketmq.name-server=127.0.0.1:9876",
                        "myddd.mq.channels.order-events.provider=rocketmq",
                        "myddd.mq.listener.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RocketMqConsumerManager.class);
                    assertThat(context).doesNotHaveBean(MessageConsumptionPlan.class);
                    assertThat(context).doesNotHaveBean(InboundDispatcher.class);
                    // 发送侧不受影响：这正是"关掉消费"而不是"关掉 MQ"。
                    assertThat(context.getBean(ProviderMessagePort.class)).isInstanceOf(RocketMqMessagePort.class);
                });
    }

    @Test
    void should_produce_no_consumer_beans_when_mq_is_disabled() {
        // AC9：总开关优先于必填校验（与 Task 35 的 AC2 对称）——故意不给 name-server。
        runner.withBean(OrderPaidListener.class, () -> new OrderPaidListener(new CopyOnWriteArrayList<>()))
                .withPropertyValues("myddd.mq.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RocketMqConsumerManager.class);
                    assertThat(context).doesNotHaveBean(RocketMqPushConsumerFactory.class);
                    assertThat(context).doesNotHaveBean(MydddRocketMqProperties.class);
                });
    }

    @Test
    void should_dispatch_a_delivered_message_end_to_end_through_the_shell() {
        // AC10：消费侧唯一的跨模块端到端钉子（真 registrar + 真 dispatcher + 真 plan + 真 manager，替身只到厂商消费者）。
        ConsumerFactoryProbe factory = new ConsumerFactoryProbe();
        List<InboundMessage> received = new CopyOnWriteArrayList<>();
        runner.withBean(RocketMqPushConsumerFactory.class, () -> factory)
                .withBean(OrderPaidListener.class, () -> new OrderPaidListener(received))
                .withPropertyValues(
                        "myddd.mq.rocketmq.name-server=127.0.0.1:9876",
                        "myddd.mq.channels.order-events.provider=rocketmq")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RecordingRocketMqPushConsumer consumer = factory.only();
                    assertThat(consumer.subscriptions()).extracting(RecordingRocketMqPushConsumer.Subscription::subExpression)
                            .containsOnly("*");

                    assertThat(consumer.deliver(RecordingRocketMqPushConsumer.wire(
                            "order-events", "{\"n\":1}", Map.of("x-a", "1"))))
                            .isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
                    assertThat(received).hasSize(1);
                    assertThat(received.get(0).channel()).isEqualTo("order-events");
                    assertThat(received.get(0).payload()).isEqualTo("{\"n\":1}");
                    assertThat(received.get(0).headers()).containsExactly(Map.entry("x-a", "1"));

                    // "没人听的通道不会被静默 ACK"的落点：壳抛 FrameworkException ⇒ 回调给厂商 RECONSUME_LATER。
                    assertThat(consumer.deliver(RecordingRocketMqPushConsumer.wire("unknown-channel", "{}")))
                            .isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
                    assertThat(received).hasSize(1);
                });
    }

    @Test
    void should_honor_an_application_provided_consumer_factory() {
        // AC11：@ConditionalOnMissingBean 的那个逃逸口的全部意义——要 ACL/RPCHook、要改 consumeFromWhere、
        // 要广播的部署贡献一个工厂 bean 即可，真实厂商接线一次都不会被构造。
        ConsumerFactoryProbe fakeFactory = new ConsumerFactoryProbe();
        runner.withBean(RocketMqPushConsumerFactory.class, () -> fakeFactory)
                .withBean(OrderPaidListener.class, () -> new OrderPaidListener(new CopyOnWriteArrayList<>()))
                .withPropertyValues(
                        "myddd.mq.rocketmq.name-server=127.0.0.1:9876",
                        "myddd.mq.channels.order-events.provider=rocketmq")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RocketMqPushConsumerFactory.class)).isSameAs(fakeFactory);
                    // manager 用的就是贡献的那个：建的消费者只有替身（数量恰一），厂商 DefaultMQPushConsumer 零构造。
                    assertThat(fakeFactory.created()).hasSize(1);
                    assertThat(fakeFactory.only().consumerGroup()).isEqualTo("g-ac");
                    assertThat(context.getBean(RocketMqConsumerManager.class).isRunning()).isTrue();
                });
    }
}
