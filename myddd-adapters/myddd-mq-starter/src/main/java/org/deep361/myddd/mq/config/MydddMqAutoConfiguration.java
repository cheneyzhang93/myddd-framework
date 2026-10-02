package org.deep361.myddd.mq.config;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.message.MessagePort;
import org.deep361.myddd.mq.MqProvider;
import org.deep361.myddd.mq.ProviderMessagePort;
import org.deep361.myddd.mq.header.OutboundMessageEnricher;
import org.deep361.myddd.mq.listener.InboundDispatcher;
import org.deep361.myddd.mq.listener.MessageListenerRegistrar;
import org.deep361.myddd.mq.payload.MessagePayloadConverter;
import org.deep361.myddd.mq.route.MessageConsumptionPlan;
import org.deep361.myddd.mq.route.MessageRouteTable;
import org.deep361.myddd.mq.route.RoutingMessagePort;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * MQ 装配壳的唯一自动配置：{@code myddd.mq.enabled}（缺省 true）控制整壳。
 *
 * <p>三条启动期快速失败，全部在装配阶段发生（而不是第一次发消息时）：
 * ① 开了 MQ 却没有任何 provider 模块；② 同一个 provider 注册了两个实现；
 * ③ 路由表引用了一个容器里没有的 provider。理由与 §0.3-6 同源：路由写错时"静默不发"比"起不来"贵得多。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "myddd.mq", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(MydddMqProperties.class)
public class MydddMqAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MessageRouteTable mydddMessageRouteTable(MydddMqProperties properties) {
        return properties.routeTable();
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(RoutingMessagePort.class)
    public MessagePort mydddRoutingMessagePort(MessageRouteTable routeTable, MydddMqProperties properties,
                                               List<ProviderMessagePort> providerPorts) {
        if (providerPorts.isEmpty()) {
            throw new FrameworkException("myddd.mq.enabled=true 但容器里没有任何 ProviderMessagePort bean："
                    + "请引入 " + MqProvider.ROCKETMQ.moduleName() + " 或 " + MqProvider.KAFKA.moduleName()
                    + "，或显式设置 myddd.mq.enabled=false");
        }
        Map<MqProvider, MessagePort> ports = new EnumMap<>(MqProvider.class);
        for (ProviderMessagePort providerPort : providerPorts) {
            MqProvider provider = providerPort.provider();
            if (provider == null) {
                throw new FrameworkException(providerPort.getClass().getName()
                        + " 的 provider() 返回 null：每个 ProviderMessagePort 必须声明自己属于哪个 provider");
            }
            if (ports.put(provider, providerPort) != null) {
                throw new FrameworkException("provider " + provider + " 有两个 ProviderMessagePort bean，"
                        + "同一能力只许一份自动装配产出（§0.3-6）");
            }
        }
        for (MqProvider referenced : routeTable.referencedProviders()) {
            if (!ports.containsKey(referenced)) {
                throw new FrameworkException("路由表引用了 " + referenced + "，但 " + referenced.moduleName()
                        + " 没有就位。已就位的 provider：" + ports.keySet()
                        + "，引用它的通道：" + channelsFor(routeTable, referenced));
            }
        }
        return new RoutingMessagePort(routeTable, ports, properties.getDeliverAt().isEnabled());
    }

    /**
     * 监听器注册表。用 {@code static} bean 方法：本自动配置实例的创建不该早于业务 bean，
     * 而注册器只依赖 {@link ListableBeanFactory}（容器自身）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "myddd.mq.listener", name = "enabled", matchIfMissing = true)
    public static MessageListenerRegistrar mydddMessageListenerRegistrar(ListableBeanFactory beanFactory) {
        return new MessageListenerRegistrar(beanFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public MessagePayloadConverter mydddMessagePayloadConverter() {
        return new MessagePayloadConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboundMessageEnricher mydddOutboundMessageEnricher(MydddMqProperties properties) {
        return new OutboundMessageEnricher(properties.getTraceHeader().isEnabled());
    }

    @Bean
    @ConditionalOnProperty(prefix = "myddd.mq.listener", name = "enabled", matchIfMissing = true)
    public InboundDispatcher mydddInboundDispatcher(MessageListenerRegistrar registrar,
                                                    MessagePayloadConverter converter) {
        return new InboundDispatcher(registrar, converter);
    }

    @Bean
    @ConditionalOnProperty(prefix = "myddd.mq.listener", name = "enabled", matchIfMissing = true)
    public MessageConsumptionPlan mydddMessageConsumptionPlan(MessageRouteTable routeTable,
                                                              MessageListenerRegistrar registrar) {
        return new MessageConsumptionPlan(routeTable, registrar);
    }

    private static List<String> channelsFor(MessageRouteTable routeTable, MqProvider provider) {
        return routeTable.configuredChannels().stream().filter(channel -> routeTable.find(channel)
                .filter(provider::equals).isPresent()).toList();
    }
}
