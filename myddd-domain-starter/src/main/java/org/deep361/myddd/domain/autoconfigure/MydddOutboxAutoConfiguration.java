package org.deep361.myddd.domain.autoconfigure;

import org.deep361.myddd.domain.outbox.JacksonOutboxPayloadSerializer;
import org.deep361.myddd.domain.outbox.OutboxBackoff;
import org.deep361.myddd.domain.outbox.OutboxChannelResolver;
import org.deep361.myddd.domain.outbox.OutboxDispatchLoop;
import org.deep361.myddd.domain.outbox.OutboxDispatchOptions;
import org.deep361.myddd.domain.outbox.OutboxDispatcher;
import org.deep361.myddd.domain.outbox.OutboxPayloadSerializer;
import org.deep361.myddd.domain.outbox.OutboxRecorder;
import org.deep361.myddd.domain.outbox.OutboxStore;
import org.deep361.myddd.kernel.port.message.MessagePort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Outbox 自动配置：{@code myddd.domain.outbox.enabled=true} 时装配序列化器、通道解析器、记录器、退避与投递循环。
 *
 * <p>启用后 {@link MydddDomainEventAutoConfiguration} 会把 {@link OutboxRecorder} 注入发布器：每次发布领域事件都在
 * 同事务内落一条记录；{@link OutboxDispatchLoop} 随容器启停，把记录投给 {@link MessagePort}。
 *
 * <p><b>快速失败</b>：启用 Outbox 却没有 {@link OutboxStore}（落库介质）或 {@link MessagePort}（消息适配器）时
 * 启动直接失败；配置取值非法同样启动失败并指明配置项名。缺失时<b>先报存储</b>——没有存储就无从谈起投递。
 *
 * <p>应用声明同类型 bean 即整体替换（全部 {@code @ConditionalOnMissingBean}）。
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

    /** 投递时钟：应用声明自己的 {@link Clock} bean 即整体替换（测试可注入固定时钟）。 */
    @Bean
    @ConditionalOnMissingBean
    public Clock mydddOutboxClock() {
        return Clock.systemUTC();
    }

    /** 退避策略：指数增长 + 只向下抖动（默认 1s / ×2 / 上限 1m / 抖动 20%）。 */
    @Bean
    @ConditionalOnMissingBean
    public OutboxBackoff outboxBackoff(MydddOutboxProperties properties) {
        MydddOutboxProperties.Backoff backoff = properties.getBackoff();
        backoff.validate();
        return OutboxBackoff.exponential(backoff.getInitial(), backoff.getMultiplier(), backoff.getMax(),
                backoff.getJitterRatio(), () -> ThreadLocalRandom.current().nextDouble());
    }

    /**
     * Outbox 投递器。
     *
     * <p>先校验配置、再查存储、最后查消息端口：三者缺失都是配置错误，按"配置 → 存储 → 端口"的顺序报出，
     * 让启动日志给出的第一个原因就是最靠上游的那个。
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboxDispatcher outboxDispatcher(ObjectProvider<MessagePort> messagePort, ObjectProvider<OutboxStore> store,
                                             OutboxBackoff backoff, MydddOutboxProperties properties, Clock clock) {
        properties.validate();
        OutboxStore outboxStore = requireStore(store);
        MessagePort port = requireMessagePort(messagePort);
        return new OutboxDispatcher(port, outboxStore, backoff,
                new OutboxDispatchOptions(properties.getMaxAttempts(), properties.getBatchSize(),
                        properties.getLeaseDuration()), clock);
    }

    /** 投递循环：随容器启停；应用可声明自己的 bean 整体替换（如改用 XXL-Job 调度，见 Plan 4）。 */
    @Bean
    @ConditionalOnMissingBean
    public OutboxDispatchLoop outboxDispatchLoop(OutboxDispatcher dispatcher, MydddOutboxProperties properties) {
        properties.validate();
        return new OutboxDispatchLoop(dispatcher, properties.getPollInterval(), properties.getShutdownTimeout());
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
        return new OutboxRecorder(requireStore(outboxStore), payloadSerializer, channelResolver);
    }

    private static OutboxStore requireStore(ObjectProvider<OutboxStore> store) {
        OutboxStore available = store.getIfAvailable();
        if (available == null) {
            throw new IllegalStateException("myddd.domain.outbox.enabled=true 但上下文中没有 OutboxStore bean；"
                    + "请提供 OutboxStore 实现（Plan 3 的 myddd-persistence-starter 提供 JDBC 实现），"
                    + "或关闭 myddd.domain.outbox.enabled");
        }
        return available;
    }

    private static MessagePort requireMessagePort(ObjectProvider<MessagePort> messagePort) {
        MessagePort available = messagePort.getIfAvailable();
        if (available == null) {
            throw new IllegalStateException("myddd.domain.outbox.enabled=true 但上下文中没有 MessagePort bean；"
                    + "请引入消息适配器（Plan 4 提供 rocketmq / kafka 适配器），或关闭 myddd.domain.outbox.enabled");
        }
        return available;
    }
}
