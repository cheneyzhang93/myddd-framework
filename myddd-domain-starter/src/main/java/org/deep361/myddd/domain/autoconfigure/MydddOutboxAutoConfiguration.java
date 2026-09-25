package org.deep361.myddd.domain.autoconfigure;

import org.deep361.myddd.domain.outbox.JacksonOutboxPayloadSerializer;
import org.deep361.myddd.domain.outbox.OutboxChannelResolver;
import org.deep361.myddd.domain.outbox.OutboxPayloadSerializer;
import org.deep361.myddd.domain.outbox.OutboxRecorder;
import org.deep361.myddd.domain.outbox.OutboxStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Outbox 自动配置：{@code myddd.domain.outbox.enabled=true} 时装配序列化器、通道解析器与记录器。
 *
 * <p>启用后 {@link MydddDomainEventAutoConfiguration} 会把 {@link OutboxRecorder} 注入发布器：每次发布领域事件
 * 都会在同事务内落一条记录，由 Task 17 的投递器异步发出。
 *
 * <p><b>快速失败</b>：启用 Outbox 却没有 {@link OutboxStore} 实现时启动直接失败——不允许静默不记录。
 * 应用声明同类型 bean 即整体替换（全部 {@code @ConditionalOnMissingBean}）。
 */
@AutoConfiguration(after = MydddDomainAutoConfiguration.class)
@ConditionalOnProperty(prefix = "myddd.domain.outbox", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(MydddOutboxProperties.class)
public class MydddOutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OutboxChannelResolver outboxChannelResolver() {
        return OutboxChannelResolver.byAggregateType();
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxPayloadSerializer outboxPayloadSerializer() {
        return new JacksonOutboxPayloadSerializer();
    }

    /**
     * Outbox 记录器。
     *
     * <p>没有 {@link OutboxStore} 实现时抛 {@link IllegalStateException} 让上下文启动失败：启用可靠投递却没有
     * 落库介质属于配置错误（Plan 3 的 myddd-persistence-starter 提供 JDBC 实现）。
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboxRecorder outboxRecorder(ObjectProvider<OutboxStore> outboxStore,
                                         OutboxPayloadSerializer payloadSerializer,
                                         OutboxChannelResolver channelResolver) {
        OutboxStore store = outboxStore.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("myddd.domain.outbox.enabled=true 但上下文中没有 OutboxStore bean；"
                    + "请提供 OutboxStore 实现（Plan 3 的 myddd-persistence-starter 提供 JDBC 实现），"
                    + "或关闭 myddd.domain.outbox.enabled");
        }
        return new OutboxRecorder(store, payloadSerializer, channelResolver);
    }
}
