package org.deep361.myddd.domain.outbox;

import java.time.Instant;
import java.util.Objects;

/**
 * Outbox 记录：一条已在本事务内落库、等待投递的领域事件。
 *
 * <p><b>不可变</b>：状态流转与重试信息通过助手方法（{@link #dispatching()} / {@link #dispatched()} /
 * {@link #retrying(Instant, String)} / {@link #dead(String)}）产生新实例，存储实现只需整体覆盖。
 *
 * <p><b>字段语义</b>：
 * <ul>
 *   <li>{@code eventId}：事件唯一 id，同时作为投递消息头 {@code myddd-event-id}（消费端幂等依据）；</li>
 *   <li>{@code eventType}：事件逻辑类型名（默认二进制类名），消费端据此反序列化；</li>
 *   <li>{@code aggregateType} / {@code aggregateId}：聚合坐标，供排障与分区路由；</li>
 *   <li>{@code traceId}：发布时的链路 id，<b>允许为 null</b>（不在链路中发布属合法场景）；</li>
 *   <li>{@code occurredAt}：事件发生时间（业务时间，消费端据此排序）；</li>
 *   <li>{@code channel}：投递通道（默认按聚合类型推导，见 {@link OutboxChannelResolver}）；</li>
 *   <li>{@code payload}：事件 JSON 文本（Task 15 的格式约定）；</li>
 *   <li>{@code nextRetryAt}：最早可投递时间，初始等于 {@code createdAt}；投递完成后不再参与判定；</li>
 *   <li>{@code retryCount}：<b>已失败的投递尝试次数</b>（成功不增加；重试与死信各 +1）；</li>
 *   <li>{@code lastError}：最近一次失败原因，成功投递后清空；</li>
 *   <li>{@code createdAt}：记录创建时间（投递器按它做先进先出）。</li>
 * </ul>
 */
public record OutboxRecord(
        String eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        String traceId,
        Instant occurredAt,
        String channel,
        String payload,
        OutboxStatus status,
        int retryCount,
        Instant nextRetryAt,
        String lastError,
        Instant createdAt) {

    /** 校验必填字段与取值范围；{@code traceId} / {@code lastError} 允许为 null。 */
    public OutboxRecord {
        requireText(eventId, "eventId");
        requireText(eventType, "eventType");
        requireText(aggregateType, "aggregateType");
        requireText(aggregateId, "aggregateId");
        requireText(channel, "channel");
        requireText(payload, "payload");
        Objects.requireNonNull(occurredAt, "occurredAt 不能为 null");
        Objects.requireNonNull(status, "status 不能为 null");
        Objects.requireNonNull(createdAt, "createdAt 不能为 null");
        Objects.requireNonNull(nextRetryAt, "nextRetryAt 不能为 null");
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount 不能为负数，实际 " + retryCount);
        }
    }

    /**
     * 新建一条待投递记录：{@code status = PENDING}、{@code retryCount = 0}、{@code lastError = null}，
     * {@code nextRetryAt} 等于 {@code createdAt}（立即可投递）。
     *
     * @param eventId      事件唯一 id
     * @param eventType    事件逻辑类型名
     * @param aggregateType 聚合类型（类名）
     * @param aggregateId  聚合 id 字符串
     * @param occurredAt   事件发生时间
     * @param traceId      发布链路 id，可为 null
     * @param channel      投递通道
     * @param payload      事件 JSON 文本
     * @param createdAt    记录创建时间
     */
    public static OutboxRecord pending(String eventId, String eventType, String aggregateType, String aggregateId,
                                       Instant occurredAt, String traceId, String channel, String payload,
                                       Instant createdAt) {
        return new OutboxRecord(eventId, eventType, aggregateType, aggregateId, traceId, occurredAt, channel,
                payload, OutboxStatus.PENDING, 0, createdAt, null, createdAt);
    }

    /**
     * 认领为投递中（投递器取件时调用）。允许 {@code PENDING → DISPATCHING} 与
     * {@code DISPATCHING → DISPATCHING}（租约过期后被重新认领）。
     */
    public OutboxRecord dispatching() {
        if (status != OutboxStatus.PENDING && status != OutboxStatus.DISPATCHING) {
            throw new IllegalStateException("只有 PENDING/DISPATCHING 记录可以被认领，实际 " + status + "：" + eventId);
        }
        return with(OutboxStatus.DISPATCHING, retryCount, nextRetryAt, lastError);
    }

    /** 标记投递成功（终态）：清空 {@code lastError}，保留 {@code retryCount} 作为失败历史。 */
    public OutboxRecord dispatched() {
        requireDispatching("标记投递成功");
        return with(OutboxStatus.DISPATCHED, retryCount, nextRetryAt, null);
    }

    /**
     * 投递失败但未耗尽重试：回到 {@code PENDING}，{@code retryCount + 1}，并记录下次可投递时间与失败原因。
     */
    public OutboxRecord retrying(Instant nextRetryAt, String lastError) {
        requireDispatching("重试");
        Objects.requireNonNull(nextRetryAt, "nextRetryAt 不能为 null");
        requireText(lastError, "lastError");
        return with(OutboxStatus.PENDING, retryCount + 1, nextRetryAt, lastError);
    }

    /** 投递失败且重试耗尽：进入死信终态，{@code retryCount + 1} 并记录失败原因。 */
    public OutboxRecord dead(String lastError) {
        requireDispatching("标记死信");
        requireText(lastError, "lastError");
        return with(OutboxStatus.DEAD, retryCount + 1, nextRetryAt, lastError);
    }

    private OutboxRecord with(OutboxStatus newStatus, int newRetryCount, Instant newNextRetryAt, String newLastError) {
        return new OutboxRecord(eventId, eventType, aggregateType, aggregateId, traceId, occurredAt, channel, payload,
                newStatus, newRetryCount, newNextRetryAt, newLastError, createdAt);
    }

    private void requireDispatching(String action) {
        if (status != OutboxStatus.DISPATCHING) {
            throw new IllegalStateException("只有 DISPATCHING 记录可以" + action + "，实际 " + status + "：" + eventId);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null) {
            throw new NullPointerException(name + " 不能为 null");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
    }
}
