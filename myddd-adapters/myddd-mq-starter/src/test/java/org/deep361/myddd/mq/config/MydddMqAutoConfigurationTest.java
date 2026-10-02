package org.deep361.myddd.mq.config;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.common.trace.TraceContext;
import org.deep361.myddd.kernel.port.message.InboundMessage;
import org.deep361.myddd.kernel.port.message.MessageHeaderNames;
import org.deep361.myddd.kernel.port.message.MessagePort;
import org.deep361.myddd.kernel.port.message.OutboundMessage;
import org.deep361.myddd.mq.MqProvider;
import org.deep361.myddd.mq.ProviderMessagePort;
import org.deep361.myddd.mq.RecordingProviderMessagePort;
import org.deep361.myddd.mq.header.OutboundMessageEnricher;
import org.deep361.myddd.mq.listener.InboundDispatcher;
import org.deep361.myddd.mq.listener.MessageListenerRegistrar;
import org.deep361.myddd.mq.listener.fixture.ProbeListener;
import org.deep361.myddd.mq.payload.MessagePayloadConverter;
import org.deep361.myddd.mq.route.MessageConsumptionPlan;
import org.deep361.myddd.mq.route.MessageRouteTable;
import org.deep361.myddd.mq.route.RoutingMessagePort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 装配面：三条启动期快速失败（无 provider、同 provider 两份、路由引用缺席 provider）与
 * {@code @Primary} 这条"业务注入 MessagePort 只会拿到路由器"的收口。
 *
 * <p>用 {@code ApplicationContextRunner} 而不是真上下文（与一致性/缓存侧同一分工）：
 * 这里只问条件装配与失败措辞，真 broker 的证据归 Task 39/40 的 IT。
 */
class MydddMqAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddMqAutoConfiguration.class));

    /**
     * 消费计划要把通道分到两个 provider 上（R1），所以 AC7 以后都用"两个 provider 同时在场"的 runner；
     * 只有刻意检查单 provider/无路由形态的 AC11/AC13/AC14 才回到 {@link #runner}。
     */
    private final ApplicationContextRunner runnerWithProvider = runner
            .withBean("rocketPort", ProviderMessagePort.class,
                    () -> new RecordingProviderMessagePort(MqProvider.ROCKETMQ, true))
            .withBean("kafkaPort", ProviderMessagePort.class,
                    () -> new RecordingProviderMessagePort(MqProvider.KAFKA, false));

    @Test
    void should_produce_no_beans_when_disabled() {
        // 总开关是"引入依赖但不启用"的退路：关掉后连路由表与配置 bean 都不留，留着只会让人以为键还生效。
        runner.withPropertyValues("myddd.mq.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MessageRouteTable.class);
            assertThat(context).doesNotHaveBean(RoutingMessagePort.class);
            assertThat(context).doesNotHaveBean(MydddMqProperties.class);
        });
    }

    @Test
    void should_fail_when_no_provider_is_present() {
        // 只有壳没有 provider 是 MQ 特有的"配齐了却发不出"形态，必须点名两个模块让用户二选一。
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("myddd-mq-rocketmq-starter")
                    .hasMessageContaining("myddd-mq-kafka-starter")
                    .hasMessageContaining("myddd.mq.enabled=false");
        });
    }

    @Test
    void should_route_between_two_providers() {
        RecordingProviderMessagePort rocket = new RecordingProviderMessagePort(MqProvider.ROCKETMQ, true);
        RecordingProviderMessagePort kafka = new RecordingProviderMessagePort(MqProvider.KAFKA, false);

        // 两个 provider 同时在场是合法形态（R1：MQ 是路由不是二选一），判重的对象是"同一 provider 两份"。
        runner.withBean("rocketPort", ProviderMessagePort.class, () -> rocket)
                .withBean("kafkaPort", ProviderMessagePort.class, () -> kafka)
                .withPropertyValues(
                        "myddd.mq.channels.order-events.provider=rocketmq",
                        "myddd.mq.channels.stock-events.provider=kafka",
                        "myddd.mq.default-provider=kafka")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // @Primary 生效：容器里有三个 MessagePort（路由器 + 两个 provider），业务注入拿到的必须是路由器。
                    // 没有它这里直接 NoUniqueBeanDefinitionException，而错误会发生在业务代码里、离配置很远。
                    assertThat(context).hasSingleBean(RoutingMessagePort.class);
                    MessagePort port = context.getBean(MessagePort.class);
                    assertThat(port).isInstanceOf(RoutingMessagePort.class);

                    port.send(OutboundMessage.of("order-events", "p1"));
                    port.send(OutboundMessage.of("stock-events", "p2"));
                    port.send(OutboundMessage.of("anything-else", "p3"));

                    assertThat(rocket.sentCount()).isEqualTo(1);
                    assertThat(kafka.sentCount()).isEqualTo(2);
                    assertThat(((RoutingMessagePort) port).providerFor("order-events"))
                            .isEqualTo(MqProvider.ROCKETMQ);
                });
    }

    @Test
    void should_reject_two_beans_of_the_same_provider() {
        // 与 §0.3-6 同源：同一能力只许一份自动装配产出。两个不同 provider 合法，两个 ROCKETMQ 不合法。
        runner.withBean("rocketA", ProviderMessagePort.class,
                () -> new RecordingProviderMessagePort(MqProvider.ROCKETMQ, true))
                .withBean("rocketB", ProviderMessagePort.class,
                        () -> new RecordingProviderMessagePort(MqProvider.ROCKETMQ, true))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(FrameworkException.class)
                            .hasMessageContaining("两个 ProviderMessagePort bean");
                });
    }

    @Test
    void should_reject_a_route_to_an_absent_provider() {
        // 只放了 Kafka provider，却把通道指到 RocketMQ：起不来，且消息里同时有模块名与受害通道名。
        runner.withBean("kafkaPort", ProviderMessagePort.class,
                () -> new RecordingProviderMessagePort(MqProvider.KAFKA, false))
                .withPropertyValues("myddd.mq.channels.order-events.provider=rocketmq")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(FrameworkException.class)
                            .hasMessageContaining("myddd-mq-rocketmq-starter")
                            .hasMessageContaining("order-events");
                });
    }

    @Test
    void should_reject_a_provider_that_reports_no_identity() {
        // provider()==null 让"这个 bean 属于谁"重新掉回 bean 名命名空间，必须点名实现类而不是含糊的 NPE。
        runner.withBean("namelessPort", ProviderMessagePort.class,
                () -> new RecordingProviderMessagePort(null, false))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(FrameworkException.class)
                            .hasMessageContaining("RecordingProviderMessagePort");
                });
    }

    @Test
    void should_scan_the_listeners_of_the_running_context() {
        // 装配面的钉子只问一件事：容器刷新后注册表真的扫到了业务 bean（Task 34 的扫描挂在
        // SmartInitializingSingleton 上，漏挂的表现是"起得来但永远收不到"，只有这里的计数能提前发现）。
        runnerWithProvider.withBean(ProbeListener.class).run(context -> {
            assertThat(context).hasNotFailed();
            MessageListenerRegistrar registrar = context.getBean(MessageListenerRegistrar.class);
            assertThat(registrar.listenerCount()).isEqualTo(2);
            assertThat(registrar.channels()).containsExactly(ProbeListener.INBOUND_CHANNEL,
                    ProbeListener.PAYLOAD_CHANNEL);
            assertThat(registrar.groupOf(ProbeListener.INBOUND_CHANNEL)).isEqualTo("g-order");
        });
    }

    @Test
    void should_wire_dispatcher_converter_enricher_and_plan() {
        // 五个新 bean 必须互相用的是容器里的那一个：dispatcher 拿错 registrar 的后果是"订阅关系与注册表漂移"，
        // 装配阶段唯一的可观察证据就是"经容器的 dispatcher 投一条，容器的探针真被调用"。
        runnerWithProvider.withBean(ProbeListener.class)
                .withPropertyValues("myddd.mq.default-provider=kafka")
                .run(context -> {
                    assertThat(context).hasSingleBean(MessageListenerRegistrar.class);
                    assertThat(context).hasSingleBean(MessagePayloadConverter.class);
                    assertThat(context).hasSingleBean(OutboundMessageEnricher.class);
                    assertThat(context).hasSingleBean(InboundDispatcher.class);
                    assertThat(context).hasSingleBean(MessageConsumptionPlan.class);

                    ProbeListener probe = context.getBean(ProbeListener.class);
                    assertThat(context.getBean(InboundDispatcher.class).dispatch(
                            new InboundMessage(ProbeListener.INBOUND_CHANNEL, "{}", "m-1", null))).isEqualTo(1);
                    assertThat(probe.callCount()).isEqualTo(1);

                    // 两个通道都走缺省 provider，ROCKETMQ 一条都不订阅（适配器据此完全不启动消费者）。
                    MessageConsumptionPlan plan = context.getBean(MessageConsumptionPlan.class);
                    assertThat(plan.channelsFor(MqProvider.KAFKA)).containsExactlyInAnyOrder(
                            ProbeListener.INBOUND_CHANNEL, ProbeListener.PAYLOAD_CHANNEL);
                    assertThat(plan.channelsFor(MqProvider.ROCKETMQ)).isEmpty();
                });
    }

    @Test
    void should_honor_an_application_provided_converter() {
        // 业务要别的 JSON 约定时只有这一个逃逸口（@ConditionalOnMissingBean），所以"用了它"必须可观察：
        // 这里故意把未知字段改成不容忍——缺省约定能读通的载荷必须失败，才证明 dispatcher 走的不是壳里那个缺省 mapper。
        MessagePayloadConverter strict = new MessagePayloadConverter(JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build());
        runnerWithProvider.withBean(ProbeListener.class)
                .withBean(MessagePayloadConverter.class, () -> strict)
                .run(context -> {
                    assertThat(context).hasSingleBean(MessagePayloadConverter.class);
                    assertThat(context.getBean(MessagePayloadConverter.class)).isSameAs(strict);

                    InboundDispatcher dispatcher = context.getBean(InboundDispatcher.class);
                    ProbeListener probe = context.getBean(ProbeListener.class);
                    String clean = "{\"orderId\":\"o-1\",\"amount\":3,\"paidAt\":\"2026-09-20T00:00:00Z\"}";
                    assertThat(dispatcher.dispatch(new InboundMessage(ProbeListener.PAYLOAD_CHANNEL, clean,
                            "m-1", null))).isEqualTo(1);
                    // 自定义 mapper 仍必须按框架的 fluent/属性名绑构造器约定工作，否则"逃逸口"会变成"必须重写整套约定"。
                    assertThat(probe.payloads().getFirst().paidAt()).isEqualTo(Instant.parse("2026-09-20T00:00:00Z"));

                    assertThatThrownBy(() -> dispatcher.dispatch(new InboundMessage(ProbeListener.PAYLOAD_CHANNEL,
                            "{\"orderId\":\"o-1\",\"amount\":3,\"paidAt\":\"2026-09-20T00:00:00Z\",\"extra\":1}",
                            "m-2", null)))
                            .isInstanceOf(FrameworkException.class)
                            .hasMessageContaining(ProbeListener.OrderPaid.class.getName());
                });
    }

    @Test
    void should_start_cleanly_with_no_listener_at_all() {
        // 纯生产方应用（有 @MessageListener 之外的 MQ 用法）是合法形态：起得来、注册表空、两个 provider
        // 的订阅计划都空——provider 适配器读到空 map 就不启动消费者，不该因此报错。
        runnerWithProvider.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MessageListenerRegistrar.class).listenerCount()).isZero();
            MessageConsumptionPlan plan = context.getBean(MessageConsumptionPlan.class);
            assertThat(plan.subscriptionsFor(MqProvider.ROCKETMQ)).isEmpty();
            assertThat(plan.subscriptionsFor(MqProvider.KAFKA)).isEmpty();
        });
    }

    @Test
    void should_refuse_to_subscribe_an_unrouted_channel() {
        // Task 33 的三条快速失败都判"发得出吗"，这条判"收得到吗"：有人听的通道既无显式路由又无缺省 provider 时，
        // 应用照样起得来、消息永远躺在 broker 里。真启动时框架异常会被生命周期处理器包成 ApplicationContextException，
        // 单测直接断本类型——措辞里"没有 provider 订阅它们"才是这条用例的意义。
        runner.withBean("rocketPort", ProviderMessagePort.class,
                () -> new RecordingProviderMessagePort(MqProvider.ROCKETMQ, true))
                .withBean(ProbeListener.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MessageConsumptionPlan plan = context.getBean(MessageConsumptionPlan.class);
                    assertThatThrownBy(() -> plan.subscriptionsFor(MqProvider.ROCKETMQ))
                            .isInstanceOf(FrameworkException.class)
                            .hasMessageContaining(ProbeListener.INBOUND_CHANNEL)
                            .hasMessageContaining("myddd.mq.channels.<channel>.provider")
                            .hasMessageContaining("myddd.mq.default-provider");
                });
    }

    @Test
    void should_produce_nothing_when_mq_is_disabled() {
        // 总开关关的是整壳七个 bean（含消费侧五个）：留着 registrar 会让学生在 myddd.mq.enabled=false
        // 的应用里继续扫 @MessageListener，看起来"MQ 还在生效"。
        runner.withPropertyValues("myddd.mq.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MessageRouteTable.class);
            assertThat(context).doesNotHaveBean(RoutingMessagePort.class);
            assertThat(context).doesNotHaveBean(MessageListenerRegistrar.class);
            assertThat(context).doesNotHaveBean(MessagePayloadConverter.class);
            assertThat(context).doesNotHaveBean(OutboundMessageEnricher.class);
            assertThat(context).doesNotHaveBean(InboundDispatcher.class);
            assertThat(context).doesNotHaveBean(MessageConsumptionPlan.class);
        });
    }

    @Test
    void should_honor_an_application_provided_enricher() {
        // 补头是发送侧唯一的注入点（业务要加租户/灰度头时贡献 bean，不去改两个 provider 各自的适配器）；
        // 用一个关掉 trace 头的实例来观察口子是否成立——线程上有 traceId 时缺省 bean 必然加头，它不加。
        OutboundMessageEnricher off = new OutboundMessageEnricher(false);
        TraceContext.set("trace-on-thread");
        try {
            runner.withBean("rocketPort", ProviderMessagePort.class,
                    () -> new RecordingProviderMessagePort(MqProvider.ROCKETMQ, true))
                    .withBean(OutboundMessageEnricher.class, () -> off)
                    .run(context -> {
                        assertThat(context).hasSingleBean(OutboundMessageEnricher.class);
                        assertThat(context.getBean(OutboundMessageEnricher.class)).isSameAs(off);
                        assertThat(off.enrich(OutboundMessage.of("order-events", "p1"))
                                .header(MessageHeaderNames.TRACE_ID)).isEmpty();
                    });
        } finally {
            TraceContext.clear();
        }
    }

    @Test
    void should_keep_the_send_path_but_drop_the_consume_path_when_scanning_is_off() {
        // §1.2.1 的"只发不收"部署形态：关掉扫描只摘掉消费侧三个 bean，发送侧四个必须留着。
        // 这也是这两个开关用 @ConditionalOnProperty 而不是"构造参数解析失败"来实现的原因——
        // 后者会让 registrar 缺席时 dispatcher/plan 连锁失败，把只想发的应用判成起不来。
        runner.withBean("rocketPort", ProviderMessagePort.class,
                () -> new RecordingProviderMessagePort(MqProvider.ROCKETMQ, true))
                .withBean(ProbeListener.class)
                .withPropertyValues("myddd.mq.listener.enabled=false",
                        "myddd.mq.channels.order-events.provider=rocketmq")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(MessageListenerRegistrar.class);
                    assertThat(context).doesNotHaveBean(InboundDispatcher.class);
                    assertThat(context).doesNotHaveBean(MessageConsumptionPlan.class);
                    assertThat(context).hasSingleBean(MessageRouteTable.class);
                    assertThat(context).hasSingleBean(RoutingMessagePort.class);
                    assertThat(context).hasSingleBean(MessagePayloadConverter.class);
                    assertThat(context).hasSingleBean(OutboundMessageEnricher.class);
                    // 探针 bean 与注解都还在，只是没人扫它：关掉的是装配，不是业务代码。
                    assertThat(context.getBean(ProbeListener.class).callCount()).isZero();
                    context.getBean(MessagePort.class).send(OutboundMessage.of("order-events", "p1"));
                });
    }
}
