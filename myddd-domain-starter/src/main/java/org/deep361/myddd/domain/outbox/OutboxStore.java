package org.deep361.myddd.domain.outbox;

import java.time.Instant;
import java.util.List;

/**
 * Outbox 存储 SPI：记录的持久化、认领与更新。
 *
 * <p><b>实现方契约</b>（Plan 3 的 myddd-persistence-starter 提供 JDBC 实现；本模块测试替身为
 * {@code TransactionAwareInMemoryOutboxStore}）：
 * <ul>
 *   <li>{@link #save} 必须参与调用方的<b>当前事务</b>——事务回滚时记录一并消失；只接受
 *       {@link OutboxStatus#PENDING} 记录；同一 {@code eventId} 重复保存必须抛异常（不得静默覆盖或忽略）。</li>
 *   <li>{@link #claimDispatchable} 必须在<b>一次原子操作</b>内完成"选取 + 认领"——返回的记录必须已是
 *       {@link OutboxStatus#DISPATCHING}，并持有租约至 {@code leaseUntil}（数据库实现用行锁 / CAS 保证多实例
 *       不重复认领）。可认领 = {@code PENDING} 且 {@code nextRetryAt <= now}，或 {@code DISPATCHING} 且租约已过期
 *       （投递器崩溃恢复）。返回顺序为 {@code createdAt}、{@code eventId} 升序（先进先出）。<b>租约是本接口的
 *       概念，不落在 {@link OutboxRecord} 上</b>。</li>
 *   <li>{@link #update} 按 {@code eventId} 全量覆盖（状态流转、重试计数、下次重试时间、错误信息）；未落库的
 *       {@code eventId} 必须抛异常。</li>
 *   <li>三个方法都会被业务线程与投递线程调用，实现必须线程安全。</li>
 * </ul>
 */
public interface OutboxStore {

    /** 同事务写入一条 PENDING 记录。 */
    void save(OutboxRecord record);

    /**
     * 原子认领可投递记录（最多 {@code limit} 条），并把它们的租约续到 {@code leaseUntil}。
     *
     * @param now        当前时间（由调用方传入，便于测试与多实例时钟统一）
     * @param leaseUntil 本批记录的租约到期时间
     * @param limit      本批最大条数，必须 ≥ 1
     * @return 已认领的记录（状态为 DISPATCHING，按 createdAt、eventId 升序）
     */
    List<OutboxRecord> claimDispatchable(Instant now, Instant leaseUntil, int limit);

    /** 按 {@code eventId} 覆盖更新。 */
    void update(OutboxRecord record);
}
