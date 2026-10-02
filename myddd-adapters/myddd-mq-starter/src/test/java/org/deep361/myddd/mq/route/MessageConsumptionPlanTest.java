package org.deep361.myddd.mq.route;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.mq.MqProvider;
import org.deep361.myddd.mq.listener.MessageListenerRegistrar;
import org.deep361.myddd.mq.listener.fixture.MqContexts;
import org.deep361.myddd.mq.listener.fixture.ProbeListener;
import org.deep361.myddd.mq.listener.fixture.ShapeListeners;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/**
 * CP 组：路由决定订阅。这一组钉的是"谁该去 broker 那里拉消息"，也是 R1（MQ 是路由不是二选一）在消费侧的落点。
 */
class MessageConsumptionPlanTest {

    @Test
    void should_assign_a_monitored_channel_to_its_explicit_route() {
        MessageConsumptionPlan plan = plan(Map.of(
                ProbeListener.INBOUND_CHANNEL, MqProvider.ROCKETMQ,
                ProbeListener.PAYLOAD_CHANNEL, MqProvider.KAFKA), null, ProbeListener.class);

        // 值是注解上写的消费组：消费组只有注解这一个来源，不建 myddd.mq.groups.* 这类第二真相
        assertThat(plan.subscriptionsFor(MqProvider.ROCKETMQ)).containsExactly(entry("order-events", "g-order"));
    }

    @Test
    void should_assign_it_to_the_default_provider_when_no_explicit_route() {
        MessageConsumptionPlan plan = plan(Map.of(), MqProvider.KAFKA, ProbeListener.class);

        assertThat(plan.subscriptionsFor(MqProvider.KAFKA)).containsOnlyKeys("order-events", "order-paid");
        assertThat(plan.subscriptionsFor(MqProvider.ROCKETMQ)).isEmpty();
    }

    @Test
    void should_fail_when_a_monitored_channel_resolves_to_nothing() {
        MessageConsumptionPlan plan = plan(Map.of(), null, ProbeListener.class);

        // 没有 provider 订阅的通道＝消息永远躺在 broker 里，而应用启动一切正常：只能选择"起不来"
        assertThatThrownBy(() -> plan.subscriptionsFor(MqProvider.ROCKETMQ))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("order-events")
                .hasMessageContaining("myddd.mq.channels.<channel>.provider")
                .hasMessageContaining("myddd.mq.default-provider")
                .hasMessageContaining(MqProvider.ALLOWED_VALUES);
    }

    @Test
    void should_list_every_unrouted_channel_in_one_message() {
        MessageConsumptionPlan plan = plan(Map.of(), null, ProbeListener.class);

        // 一条消息列全：逐个抛的话，改配置的人每轮只看得见一个通道，得跑三次启动才补完
        assertThatThrownBy(() -> plan.subscriptionsFor(MqProvider.ROCKETMQ))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("有 2 个")
                .hasMessageContaining("order-events")
                .hasMessageContaining("order-paid");
    }

    @Test
    void should_split_channels_between_providers_without_overlap() {
        MessageListenerRegistrar registrar = registrar(ProbeListener.class);
        MessageConsumptionPlan plan = new MessageConsumptionPlan(new MessageRouteTable(Map.of(
                ProbeListener.INBOUND_CHANNEL, MqProvider.ROCKETMQ,
                ProbeListener.PAYLOAD_CHANNEL, MqProvider.KAFKA), null), registrar);

        Set<String> rocket = plan.channelsFor(MqProvider.ROCKETMQ);
        Set<String> kafka = plan.channelsFor(MqProvider.KAFKA);
        assertThat(rocket).containsExactly("order-events");
        assertThat(kafka).containsExactly("order-paid");
        assertThat(rocket).doesNotContainAnyElementsOf(kafka);
        // 两侧并集必须正好等于被监听的通道：一个通道要么有人订阅，要么启动就失败（CP3），不许悬空
        assertThat(Stream.concat(rocket.stream(), kafka.stream()).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(registrar.channels());
    }

    @Test
    void should_return_empty_for_a_provider_that_listens_to_nothing() {
        MessageConsumptionPlan plan = plan(Map.of(), null, ShapeListeners.Plain.class);

        // 一个通道都不负责是合法形态：适配器据此不启动消费者，而不是启动一个空转的消费者
        assertThat(plan.subscriptionsFor(MqProvider.ROCKETMQ)).isEmpty();
        assertThat(plan.subscriptionsFor(MqProvider.KAFKA)).isEmpty();
        assertThat(plan.channelsFor(MqProvider.ROCKETMQ)).isEmpty();
    }

    @Test
    void should_report_an_empty_group_as_the_default_group_marker() {
        MessageConsumptionPlan plan = plan(Map.of(), MqProvider.KAFKA, ProbeListener.class);

        // 注解没写 group ⇒ 值是空串（不是 null）：空串＝"用适配器的缺省消费组"
        assertThat(plan.subscriptionsFor(MqProvider.KAFKA)).containsEntry(ProbeListener.PAYLOAD_CHANNEL, "");
        assertThat(plan.subscriptionsFor(MqProvider.KAFKA).get(ProbeListener.PAYLOAD_CHANNEL)).isNotNull();
        assertThatThrownBy(() -> plan.subscriptionsFor(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("provider");
    }

    private static MessageConsumptionPlan plan(Map<String, MqProvider> exactRoutes, MqProvider fallback,
                                               Class<?>... listenerBeans) {
        return new MessageConsumptionPlan(new MessageRouteTable(exactRoutes, fallback), registrar(listenerBeans));
    }

    private static MessageListenerRegistrar registrar(Class<?>... listenerBeans) {
        return MqContexts.registrarOf(MqContexts.refresh(listenerBeans));
    }
}
