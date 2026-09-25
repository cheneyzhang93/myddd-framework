package org.deep361.myddd.domain.outbox;

import org.deep361.myddd.kernel.common.trace.TraceContext;
import org.deep361.myddd.kernel.ddd.event.DomainEvent;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Outbox 记录器：把领域事件序列化成 payload，并在<b>当前事务内</b>写入一条 {@link OutboxStatus#PENDING} 记录。
 *
 * <p><b>同事务契约（本类存在的理由）</b>：{@link #record} 必须在业务事务内调用——业务改动与 Outbox 记录要么
 * 一起提交、要么一起回滚。当前线程没有活动事务时直接快速失败（{@link IllegalStateException}），本框架不允许
 * "业务改完了但消息没记下来"。
 *
 * <p><b>失败语义</b>：通道空白、序列化失败、落库失败一律原样上抛让业务事务回滚——本类不吞异常、不做补偿重试
 * （重试是投递器的职责，见 Task 17 的 OutboxDispatcher）。
 *
 * <p><b>线程安全</b>：本类无可变状态；事务绑定由 {@link OutboxStore} 实现负责。
 */
public final class OutboxRecorder {

    private final OutboxStore store;
    private final OutboxPayloadSerializer serializer;
    private final OutboxChannelResolver channelResolver;
    private final Clock clock;

    /** 使用系统 UTC 时钟构造（生产路径）。 */
    public OutboxRecorder(OutboxStore store, OutboxPayloadSerializer serializer,
                          OutboxChannelResolver channelResolver) {
        this(store, serializer, channelResolver, Clock.systemUTC());
    }

    /** 使用指定时钟构造（测试注入固定时钟以断言 createdAt / nextRetryAt）。 */
    public OutboxRecorder(OutboxStore store, OutboxPayloadSerializer serializer,
                          OutboxChannelResolver channelResolver, Clock clock) {
        this.store = Objects.requireNonNull(store, "OutboxStore 不能为 null");
        this.serializer = Objects.requireNonNull(serializer, "OutboxPayloadSerializer 不能为 null");
        this.channelResolver = Objects.requireNonNull(channelResolver, "OutboxChannelResolver 不能为 null");
        this.clock = Objects.requireNonNull(clock, "Clock 不能为 null");
    }

    /**
     * 在当前事务内记录一条领域事件。
     *
     * @param event         领域事件，不可为 null
     * @param aggregateType 聚合类型（聚合根类名），不可为 null
     * @param aggregateId   聚合 id 字符串形式，不可为 null
     * @return 已落库的 PENDING 记录（供调用方观测）
     * @throws IllegalStateException 当前线程没有活动事务，或通道解析器返回了空白通道
     */
    public OutboxRecord record(DomainEvent event, String aggregateType, String aggregateId) {
        Objects.requireNonNull(event, "event 不能为 null");
        Objects.requireNonNull(aggregateType, "aggregateType 不能为 null");
        Objects.requireNonNull(aggregateId, "aggregateId 不能为 null");
        requireActiveTransaction();
        String payload = serializer.serialize(event);
        String channel = channelResolver.resolve(event, aggregateType);
        if (channel == null || channel.isBlank()) {
            throw new IllegalStateException("OutboxChannelResolver 返回了空白通道：aggregateType=" + aggregateType);
        }
        Instant createdAt = clock.instant();
        OutboxRecord record = OutboxRecord.pending(event.eventId(), event.eventType(), aggregateType, aggregateId,
                event.occurredAt(), TraceContext.currentTraceId(), channel, payload, createdAt);
        store.save(record);
        return record;
    }

    private static void requireActiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox 记录必须在事务内执行：当前线程没有活动事务，"
                    + "业务改动与 Outbox 记录无法原子提交；请在 @Transactional / TransactionTemplate 内发布领域事件");
        }
    }
}
