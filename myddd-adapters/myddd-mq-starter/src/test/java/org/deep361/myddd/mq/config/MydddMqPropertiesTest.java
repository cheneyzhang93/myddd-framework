package org.deep361.myddd.mq.config;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.mq.MqProvider;
import org.deep361.myddd.mq.route.MessageRouteTable;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 启动期校验的唯一落点：每个非法键都要在 {@link MydddMqProperties#validate()} 里变成
 * "哪个键、配了什么、允许什么"的三段式诊断（这就是路由绑 {@code String} 而非枚举的理由）。
 */
class MydddMqPropertiesTest {

    private static MydddMqProperties.Channel channel(String provider) {
        MydddMqProperties.Channel spec = new MydddMqProperties.Channel();
        spec.setProvider(provider);
        return spec;
    }

    @Test
    void should_default_to_enabled_with_delayed_delivery_on() {
        MydddMqProperties properties = new MydddMqProperties();

        // 缺省即"整壳可用"：引入依赖就是表态，开关只留作退路（§0.3-6 的反向：不配也能跑）。
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getDeliverAt().isEnabled()).isTrue();
        assertThat(properties.getChannels()).isEmpty();
        assertThat(properties.getDefaultProvider()).isNull();
    }

    @Test
    void should_default_the_listener_and_trace_header_switches_to_on() {
        MydddMqProperties properties = new MydddMqProperties();

        // 与 deliverAt 同形：两个块都要非 null 且缺省为 true，否则绑定 `myddd.mq.listener.enabled=false`
        // 时嵌套块为 null 会 NPE，而"缺省关"会让只引依赖的应用静默失去消费能力（§1.2.1 的反向）。
        assertThat(properties.getListener()).isNotNull();
        assertThat(properties.getTraceHeader()).isNotNull();
        assertThat(properties.getListener().isEnabled()).isTrue();
        assertThat(properties.getTraceHeader().isEnabled()).isTrue();

        properties.getListener().setEnabled(false);
        properties.getTraceHeader().setEnabled(false);
        assertThat(properties.getListener().isEnabled()).isFalse();
        assertThat(properties.getTraceHeader().isEnabled()).isFalse();
    }

    @Test
    void should_reject_an_illegal_route_value_naming_the_channel() {
        MydddMqProperties properties = new MydddMqProperties();
        properties.setChannels(Map.of("order-events", channel("rabbit")));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.mq.channels.order-events.provider")
                .hasMessageContaining("rabbit")
                .hasMessageContaining(MqProvider.ALLOWED_VALUES);
    }

    @Test
    void should_reject_an_illegal_default_provider_naming_the_key() {
        MydddMqProperties properties = new MydddMqProperties();
        properties.setDefaultProvider("rabbitmq");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.mq.default-provider")
                .hasMessageContaining("rabbitmq")
                .hasMessageContaining(MqProvider.ALLOWED_VALUES);
    }

    @Test
    void should_reject_a_blank_channel_key_and_a_missing_provider_value() {
        MydddMqProperties blankKey = new MydddMqProperties();
        Map<String, MydddMqProperties.Channel> channels = new LinkedHashMap<>();
        channels.put("  ", channel("kafka"));
        blankKey.setChannels(channels);

        // 空白通道名配了也没人路由得到，它只会占住一条"看起来配了什么"的键。
        assertThatThrownBy(blankKey::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("通道名不能为空白")
                .hasMessageContaining("kafka");

        MydddMqProperties missingProvider = new MydddMqProperties();
        missingProvider.setChannels(Map.of("order-events", channel(null)));
        assertThatThrownBy(missingProvider::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.mq.channels.order-events.provider 必填")
                .hasMessageContaining(MqProvider.ALLOWED_VALUES);
    }

    @Test
    void should_leave_the_default_provider_unset_when_it_is_blank() {
        MydddMqProperties properties = new MydddMqProperties();
        properties.setDefaultProvider("  ");

        // 空白缺省 = 没配缺省（而不是"解析出一个空 provider"）：未命中即回落到 RS3 的快速失败。
        properties.validate();
        MessageRouteTable routeTable = properties.routeTable();
        assertThat(routeTable.hasFallback()).isFalse();
        assertThatThrownBy(() -> routeTable.resolve("order-events"))
                .isInstanceOf(FrameworkException.class);
    }

    @Test
    void should_build_a_route_table_from_the_parsed_values() {
        MydddMqProperties properties = new MydddMqProperties();
        Map<String, MydddMqProperties.Channel> channels = new LinkedHashMap<>();
        channels.put("order-events", channel("RocketMQ"));
        channels.put("stock-events", channel("rocketmq"));
        properties.setChannels(channels);
        properties.setDefaultProvider("kafka");

        properties.validate();
        MessageRouteTable routeTable = properties.routeTable();

        // 两条通道共用 ROCKETMQ，加上缺省 KAFKA：引用集只剩两个不同值——去重发生在解析，不在消费方。
        assertThat(routeTable.resolve("order-events")).isEqualTo(MqProvider.ROCKETMQ);
        assertThat(routeTable.resolve("stock-events")).isEqualTo(MqProvider.ROCKETMQ);
        assertThat(routeTable.resolve("anything-else")).isEqualTo(MqProvider.KAFKA);
        assertThat(routeTable.referencedProviders())
                .containsExactlyInAnyOrder(MqProvider.ROCKETMQ, MqProvider.KAFKA);
    }
}
