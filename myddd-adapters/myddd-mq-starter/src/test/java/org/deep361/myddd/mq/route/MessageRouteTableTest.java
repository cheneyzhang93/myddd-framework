package org.deep361.myddd.mq.route;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.mq.MqProvider;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 路由表的查找规则与"取不到路由时怎么说清楚"（失败消息的三段式：通道名、已配置清单、两条补救键）。
 */
class MessageRouteTableTest {

    private static MessageRouteTable table(Map<String, MqProvider> routes, MqProvider fallback) {
        return new MessageRouteTable(routes, fallback);
    }

    @Test
    void should_prefer_an_exact_channel_route() {
        // 精确命中优先于缺省：这是 R1"按通道路由"的主路径，缺省永远不许盖掉显式配置。
        MessageRouteTable routeTable =
                table(Map.of("order-events", MqProvider.ROCKETMQ), MqProvider.KAFKA);

        assertThat(routeTable.resolve("order-events")).isEqualTo(MqProvider.ROCKETMQ);
        assertThat(routeTable.find("order-events")).contains(MqProvider.ROCKETMQ);
    }

    @Test
    void should_fall_back_to_the_default_provider() {
        MessageRouteTable routeTable =
                table(Map.of("order-events", MqProvider.ROCKETMQ), MqProvider.KAFKA);

        assertThat(routeTable.resolve("unknown-channel")).isEqualTo(MqProvider.KAFKA);
        assertThat(routeTable.hasFallback()).isTrue();
    }

    @Test
    void should_fail_loudly_when_neither_hits_and_no_default_is_configured() {
        MessageRouteTable routeTable = table(Map.of("pay-events", MqProvider.KAFKA), null);

        assertThatThrownBy(() -> routeTable.resolve("order-events"))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("order-events")
                .hasMessageContaining("myddd.mq.channels.order-events.provider")
                .hasMessageContaining("myddd.mq.default-provider")
                .hasMessageContaining(MqProvider.ALLOWED_VALUES)
                // 已配置通道清单也要在消息里：用户要的是"还有哪些键可看"，不是重新 grep 配置文件。
                .hasMessageContaining("pay-events");
    }

    @Test
    void should_reference_both_the_explicit_routes_and_the_default() {
        MessageRouteTable withFallback = table(
                Map.of("a-events", MqProvider.ROCKETMQ, "b-events", MqProvider.KAFKA), MqProvider.ROCKETMQ);
        assertThat(withFallback.referencedProviders())
                .containsExactlyInAnyOrder(MqProvider.ROCKETMQ, MqProvider.KAFKA);

        MessageRouteTable withoutFallback = table(Map.of("a-events", MqProvider.ROCKETMQ), null);
        assertThat(withoutFallback.referencedProviders()).containsExactly(MqProvider.ROCKETMQ);
        assertThat(withoutFallback.hasFallback()).isFalse();
    }

    @Test
    void should_expose_configured_channels_in_a_stable_order() {
        // 故意按降序插入：升序输出必须由表自己保证，而不是碰巧跟着插入序。
        Map<String, MqProvider> input = new LinkedHashMap<>();
        input.put("order-events", MqProvider.ROCKETMQ);
        input.put("alpha-events", MqProvider.KAFKA);
        MessageRouteTable routeTable = table(input, null);

        assertThat(routeTable.configuredChannels()).containsExactly("alpha-events", "order-events");
        assertThatThrownBy(() -> routeTable.configuredChannels().add("late"))
                .isInstanceOf(UnsupportedOperationException.class);

        // 防御性拷贝：建表之后改入参，表不许跟着变——路由表是启动期定稿的不可变事实。
        input.put("zulu-events", MqProvider.KAFKA);
        input.remove("order-events");
        assertThat(routeTable.configuredChannels()).containsExactly("alpha-events", "order-events");
        assertThat(routeTable.resolve("order-events")).isEqualTo(MqProvider.ROCKETMQ);
    }

    @Test
    void should_reject_a_blank_channel_when_resolving() {
        MessageRouteTable routeTable = table(Map.of("order-events", MqProvider.ROCKETMQ), MqProvider.KAFKA);

        // 空白通道是调用方缺陷而不是"没配路由"，抛 IllegalArgumentException 而不是 FrameworkException：
        // 缺省的兜底也不许被一个空白键触发去发消息。
        assertThatThrownBy(() -> routeTable.resolve(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel 不能为空白");
        assertThatThrownBy(() -> routeTable.resolve(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel 不能为空白");
    }
}
