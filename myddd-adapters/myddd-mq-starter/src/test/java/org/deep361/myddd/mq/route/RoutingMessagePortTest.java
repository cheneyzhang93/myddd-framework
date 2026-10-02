package org.deep361.myddd.mq.route;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.message.MessagePort;
import org.deep361.myddd.kernel.port.message.OutboundMessage;
import org.deep361.myddd.mq.MqProvider;
import org.deep361.myddd.mq.RecordingProviderMessagePort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 路由器的三件职责：解析路由、执行 R6 的延迟投递开关、把装配期缺陷在运行期也说清楚。
 */
class RoutingMessagePortTest {

    private static final Instant DELIVER_AT = Instant.parse("2026-10-01T08:00:00Z");

    private final RecordingProviderMessagePort rocketPort =
            new RecordingProviderMessagePort(MqProvider.ROCKETMQ, true);
    private final RecordingProviderMessagePort kafkaPort =
            new RecordingProviderMessagePort(MqProvider.KAFKA, false);

    @Test
    void should_delegate_to_the_resolved_provider_only() {
        // 两个 provider 都在场，消息只走路由命中的那一个——"路由"不是"广播"（R1）。
        RoutingMessagePort port = port(
                routeTable(Map.of("order-events", MqProvider.ROCKETMQ), MqProvider.KAFKA),
                ports(rocketPort, kafkaPort), true);
        OutboundMessage message = OutboundMessage.of("order-events", "payload");

        port.send(message);

        assertThat(rocketPort.sentCount()).isEqualTo(1);
        assertThat(rocketPort.sentMessages()).containsExactly(message);
        assertThat(kafkaPort.sentCount()).isZero();
    }

    @Test
    void should_reject_delayed_delivery_when_the_switch_is_off() {
        RoutingMessagePort port = port(
                routeTable(Map.of("order-events", MqProvider.ROCKETMQ), null),
                ports(rocketPort, kafkaPort), false);

        Throwable thrown = catchThrowable(() -> port.send(
                new OutboundMessage("order-events", "payload", null, null, DELIVER_AT)));

        // 拒发必须是业务可预期异常（调用方的"稍后再发"不是框架装配错误），且在任何 provider 计数之前。
        assertThat(thrown).isInstanceOf(BizException.class);
        assertThat(((BizException) thrown).getErrorCode()).isEqualTo(CommonErrorCode.ARGUMENT_INVALID);
        assertThat(thrown).hasMessageContaining("deliverAt")
                .hasMessageContaining("myddd.mq.deliver-at.enabled");
        assertThat(rocketPort.sentCount()).isZero();
        assertThat(kafkaPort.sentCount()).isZero();
    }

    @Test
    void should_pass_delayed_delivery_through_when_the_switch_is_on() {
        // 开关开 ⇒ 壳放行；"该 provider 到底支不支持"的最终拒发在 provider 侧（Task 35/37），壳不替它回答。
        RoutingMessagePort port = port(
                routeTable(Map.of("order-events", MqProvider.ROCKETMQ), null),
                ports(rocketPort, kafkaPort), true);

        port.send(new OutboundMessage("order-events", "payload", null, null, DELIVER_AT));

        assertThat(rocketPort.sentCount()).isEqualTo(1);
    }

    @Test
    void should_fail_when_the_route_points_at_a_provider_that_is_not_present() {
        RoutingMessagePort port = port(
                routeTable(Map.of("order-events", MqProvider.ROCKETMQ), null),
                ports(kafkaPort), true);

        assertThatThrownBy(() -> port.send(OutboundMessage.of("order-events", "payload")))
                .isInstanceOf(FrameworkException.class)
                // 指路模块名：这句失败要能直接决定"引哪个 jar"。
                .hasMessageContaining("myddd-mq-rocketmq-starter")
                .hasMessageContaining("order-events");
        assertThat(kafkaPort.sentCount()).isZero();
    }

    @Test
    void should_reject_a_null_message() {
        RoutingMessagePort port = port(routeTable(Map.of(), MqProvider.KAFKA), ports(kafkaPort), true);

        assertThatThrownBy(() -> port.send(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("message");
    }

    @Test
    void should_report_the_provider_for_a_channel() {
        // 观测缝与真实投递必须同一套规则：providerFor 就委托 resolve，不许有第二份查找逻辑。
        RoutingMessagePort port = port(
                routeTable(Map.of("order-events", MqProvider.ROCKETMQ), MqProvider.KAFKA),
                ports(rocketPort, kafkaPort), true);

        assertThat(port.providerFor("order-events")).isEqualTo(MqProvider.ROCKETMQ);
        assertThat(port.providerFor("whatever")).isEqualTo(MqProvider.KAFKA);

        RoutingMessagePort noRoute = port(routeTable(Map.of(), null), ports(rocketPort), true);
        assertThatThrownBy(() -> noRoute.providerFor("order-events"))
                .isInstanceOf(FrameworkException.class);
    }

    @Test
    void should_survive_an_immutable_provider_map() {
        Map<MqProvider, MessagePort> input = new LinkedHashMap<>();
        input.put(MqProvider.KAFKA, kafkaPort);
        RoutingMessagePort port = port(
                routeTable(Map.of("order-events", MqProvider.ROCKETMQ), MqProvider.KAFKA), input, true);

        // 构造后再往入参里塞 RocketMQ：路由器若做了 Map.copyOf 就不该看见它，未就位照样快速失败。
        input.put(MqProvider.ROCKETMQ, rocketPort);

        assertThatThrownBy(() -> port.send(OutboundMessage.of("order-events", "payload")))
                .isInstanceOf(FrameworkException.class);
        assertThat(rocketPort.sentCount()).isZero();
        assertThat(kafkaPort.sentCount()).isZero();
    }

    private static MessageRouteTable routeTable(Map<String, MqProvider> routes, MqProvider fallback) {
        return new MessageRouteTable(routes, fallback);
    }

    private static Map<MqProvider, MessagePort> ports(RecordingProviderMessagePort... providerPorts) {
        Map<MqProvider, MessagePort> map = new EnumMap<>(MqProvider.class);
        for (RecordingProviderMessagePort providerPort : providerPorts) {
            map.put(providerPort.provider(), providerPort);
        }
        return map;
    }

    private static RoutingMessagePort port(MessageRouteTable routeTable, Map<MqProvider, MessagePort> ports,
                                           boolean delayedDeliveryEnabled) {
        return new RoutingMessagePort(routeTable, ports, delayedDeliveryEnabled);
    }
}
