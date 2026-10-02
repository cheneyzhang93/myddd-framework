package org.deep361.myddd.mq.config;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.mq.MqProvider;
import org.deep361.myddd.mq.route.MessageRouteTable;
import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code myddd.mq.*}：路由与能力开关。三方键（RocketMQ/SCA 的、{@code spring.kafka.*}）一概不在此出现（§0.3-5）。
 *
 * <p>路由值刻意绑成 {@code String} 而不是枚举：枚举绑定失败时 Spring 给的是转换异常，
 * 不含"哪个键、配了什么、允许什么"这三段——而这三段正是本框架对配置错误的承诺（Plan 3 已交付的诊断格式）。
 *
 * <p>注意：通道名含 {@code .} 时，properties 里必须写成括号形式 {@code myddd.mq.channels[order.events].provider=kafka}，
 * 直接写点号会被 Spring Boot 当成嵌套层级（这是 relaxed binding 的既有行为，不是本框架的坑）。
 */
@ConfigurationProperties(prefix = "myddd.mq")
public class MydddMqProperties {

    /** 关掉则本壳不产出任何 bean（含 {@code RoutingMessagePort}）。 */
    private boolean enabled = true;

    /** 未命中显式路由时使用的 provider；留空表示"没配缺省"，此时未命中即快速失败。 */
    private String defaultProvider;

    /** 通道 → 该通道的 provider（键名与内核 {@code @MessageListener} 文档里的 {@code myddd.mq.channels.<channel>.provider} 逐字一致）。 */
    private Map<String, Channel> channels = new LinkedHashMap<>();

    private final DeliverAt deliverAt = new DeliverAt();

    /** {@code myddd.mq.listener.*}：{@code @MessageListener} 扫描与消费侧装配的总开关。 */
    private final Listener listener = new Listener();

    /** {@code myddd.mq.trace-header.*}：出站 traceId 头开关（关掉只用于诊断，链路会在 MQ 这一跳断开）。 */
    private final TraceHeader traceHeader = new TraceHeader();

    /** 校验后的解析结果（{@link #channels} 与 {@link #defaultProvider} 的枚举形态）。 */
    private Map<String, MqProvider> parsedChannels = Map.of();
    private MqProvider parsedDefaultProvider;

    /** {@code myddd.mq.channels.<channel>}：一条通道的配置。 */
    public static class Channel {

        /** 该通道的 provider，必填。 */
        private String provider;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }
    }

    /** {@code myddd.mq.deliver-at.*}：定点投递的能力开关（R6）。 */
    public static class DeliverAt {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** {@code myddd.mq.listener.enabled}（缺省 true）。关掉后壳不产出注册表/分发器/消费计划，provider 一个通道都不订阅。 */
    public static class Listener {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** {@code myddd.mq.trace-header.enabled}（缺省 true）。 */
    public static class TraceHeader {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    @PostConstruct
    void validate() {
        Map<String, MqProvider> parsed = new LinkedHashMap<>();
        channels.forEach((channel, spec) -> {
            if (channel == null || channel.isBlank()) {
                throw new FrameworkException("myddd.mq.channels 的通道名不能为空白（该条的 provider 值："
                        + (spec == null ? "null" : spec.getProvider()) + "）");
            }
            if (spec == null || spec.getProvider() == null || spec.getProvider().isBlank()) {
                throw new FrameworkException("myddd.mq.channels." + channel + ".provider 必填，可取值："
                        + MqProvider.ALLOWED_VALUES);
            }
            try {
                parsed.put(channel, MqProvider.parse(spec.getProvider()));
            } catch (IllegalArgumentException e) {
                throw new FrameworkException("myddd.mq.channels." + channel + ".provider 配置非法："
                        + e.getMessage(), e);
            }
        });
        if (defaultProvider != null && !defaultProvider.isBlank()) {
            try {
                parsedDefaultProvider = MqProvider.parse(defaultProvider);
            } catch (IllegalArgumentException e) {
                throw new FrameworkException(
                        "myddd.mq.default-provider 配置非法：" + e.getMessage(), e);
            }
        }
        parsedChannels = Map.copyOf(parsed);
    }

    /** 校验后的路由表（只在 {@link #validate()} 之后调用）。 */
    public MessageRouteTable routeTable() {
        return new MessageRouteTable(parsedChannels, parsedDefaultProvider);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public Map<String, Channel> getChannels() {
        return channels;
    }

    public void setChannels(Map<String, Channel> channels) {
        this.channels = channels;
    }

    public DeliverAt getDeliverAt() {
        return deliverAt;
    }

    public Listener getListener() {
        return listener;
    }

    public TraceHeader getTraceHeader() {
        return traceHeader;
    }
}
