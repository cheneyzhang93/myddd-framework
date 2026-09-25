package org.deep361.myddd.domain.outbox;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 事务感知的内存 Outbox 存储（测试替身；Plan 3 的 myddd-persistence-starter 提供 JDBC 实现）。
 *
 * <p><b>为什么需要它</b>：Outbox 的核心不变量是"业务改动与 Outbox 记录同事务提交/回滚"。本替身用线程绑定的
 * 事务工作副本模拟：事务内首次写入时把已提交视图拷贝成工作副本并绑定到事务；提交（{@code afterCommit}）时
 * 整体合并回已提交视图，回滚（{@code afterCompletion(ROLLED_BACK)}）时丢弃工作副本。任何"先记后滚"式的实现
 * 偏差都会被 {@code OutboxRecorderTest} 与 {@code SpringDomainEventPublisherTest} 捕获。
 *
 * <p><b>并发边界</b>：方法全部同步，可被投递线程调用；但事务进行中并发认领/更新会产生"合并覆盖"（替身不做
 * 冲突检测——Plan 3 的 JDBC 实现用行锁/乐观版本号处理）。Plan 2 的测试以确定性调用驱动，不触发该路径。
 *
 * <p><b>租约</b>：与 {@link OutboxStore} 契约一致，租约不落在 {@link OutboxRecord} 上，用内部
 * {@code leaseUntilByEventId} 维护；认领即续租，过期可被重新认领（模拟"投递器崩溃后恢复"）。
 *
 * <p>测试结束请调用 {@link #reset()}（清记录 + 解绑当前线程资源）。投递循环类用例用
 * {@link #awaitStatus(String, OutboxStatus, Duration)} 等记录的终态：投递器"发送成功"与"标记"是两步，
 * 在消息端口上被唤醒后直接读记录会读到中间态。
 */
public final class TransactionAwareInMemoryOutboxStore implements OutboxStore {

    private final Map<String, OutboxRecord> committedRows = new LinkedHashMap<>();
    private final Map<String, Instant> leaseUntilByEventId = new HashMap<>();

    @Override
    public synchronized void save(OutboxRecord record) {
        Objects.requireNonNull(record, "record 不能为 null");
        if (record.status() != OutboxStatus.PENDING) {
            throw new IllegalArgumentException("save 只接受 PENDING 记录，实际 " + record.status()
                    + "：" + record.eventId());
        }
        Map<String, OutboxRecord> rows = workingCopy();
        if (rows.containsKey(record.eventId())) {
            throw new IllegalStateException("Outbox 记录已存在（同一 eventId 重复保存）：" + record.eventId());
        }
        rows.put(record.eventId(), record);
        notifyAll();
    }

    @Override
    public synchronized List<OutboxRecord> claimDispatchable(Instant now, Instant leaseUntil, int limit) {
        Objects.requireNonNull(now, "now 不能为 null");
        Objects.requireNonNull(leaseUntil, "leaseUntil 不能为 null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须 ≥ 1，实际 " + limit);
        }
        List<OutboxRecord> claimed = new ArrayList<>();
        for (OutboxRecord record : dispatchOrder(committedRows)) {
            if (claimed.size() >= limit) {
                break;
            }
            if (!claimable(record, now)) {
                continue;
            }
            OutboxRecord dispatching = record.dispatching();
            committedRows.put(dispatching.eventId(), dispatching);
            leaseUntilByEventId.put(dispatching.eventId(), leaseUntil);
            claimed.add(dispatching);
        }
        if (!claimed.isEmpty()) {
            notifyAll();
        }
        return claimed;
    }

    @Override
    public synchronized void update(OutboxRecord record) {
        Objects.requireNonNull(record, "record 不能为 null");
        if (!committedRows.containsKey(record.eventId())) {
            throw new IllegalArgumentException("Outbox 记录不存在，无法更新：" + record.eventId());
        }
        committedRows.put(record.eventId(), record);
        if (record.status() != OutboxStatus.DISPATCHING) {
            leaseUntilByEventId.remove(record.eventId());
        }
        notifyAll();
    }

    /**
     * 等待某条已落库记录进入期望状态（投递循环用例的确定性同步点；等待/通知式，不用 sleep 轮询）。
     *
     * <p><b>为什么需要它</b>：投递器"发送成功"与"标记 DISPATCHED"是两步（先 {@code send} 后 {@code update}，
     * 见至少一次语义）——测试线程在消息端口上被唤醒时可能读到中间态 {@code DISPATCHING}。本方法盯住记录的
     * 终态，超时返回 {@code false} 交给调用方断言：既不假通过，也不因调度时序假红。
     *
     * @param eventId 记录 id
     * @param status  期望状态
     * @param timeout 等待上限
     * @return 到达期望状态返回 true；{@code timeout} 内未到达返回 false
     */
    public synchronized boolean awaitStatus(String eventId, OutboxStatus status, Duration timeout)
            throws InterruptedException {
        Objects.requireNonNull(eventId, "eventId 不能为 null");
        Objects.requireNonNull(status, "status 不能为 null");
        Objects.requireNonNull(timeout, "timeout 不能为 null");
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!hasStatus(eventId, status)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        return true;
    }

    private boolean hasStatus(String eventId, OutboxStatus status) {
        OutboxRecord record = committedRows.get(eventId);
        return record != null && record.status() == status;
    }

    /** 已提交视图（按 createdAt、eventId 升序的不可变快照），供测试断言使用。 */
    public synchronized List<OutboxRecord> committedRecords() {
        return dispatchOrder(committedRows);
    }

    /** 清空记录并解绑当前线程的事务资源。 */
    public synchronized void reset() {
        committedRows.clear();
        leaseUntilByEventId.clear();
        TransactionSynchronizationManager.unbindResourceIfPossible(this);
        notifyAll();
    }

    private boolean claimable(OutboxRecord record, Instant now) {
        if (record.status() == OutboxStatus.PENDING) {
            return !record.nextRetryAt().isAfter(now);
        }
        if (record.status() == OutboxStatus.DISPATCHING) {
            Instant leaseUntil = leaseUntilByEventId.get(record.eventId());
            return leaseUntil == null || !leaseUntil.isAfter(now);
        }
        return false;
    }

    private static List<OutboxRecord> dispatchOrder(Map<String, OutboxRecord> rows) {
        return rows.values().stream()
                .sorted(Comparator.comparing(OutboxRecord::createdAt).thenComparing(OutboxRecord::eventId))
                .toList();
    }

    /** 事务内返回工作副本（首次访问时建立绑定与同步回调）；无事务时直接返回已提交视图。 */
    @SuppressWarnings("unchecked")
    private Map<String, OutboxRecord> workingCopy() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return committedRows;
        }
        Map<String, OutboxRecord> bound =
                (Map<String, OutboxRecord>) TransactionSynchronizationManager.getResource(this);
        if (bound != null) {
            return bound;
        }
        Map<String, OutboxRecord> working = new LinkedHashMap<>(committedRows);
        TransactionSynchronizationManager.bindResource(this, working);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                mergeWorkingCopy(working);
                TransactionSynchronizationManager.unbindResourceIfPossible(TransactionAwareInMemoryOutboxStore.this);
            }

            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(TransactionAwareInMemoryOutboxStore.this);
            }
        });
        return working;
    }

    private synchronized void mergeWorkingCopy(Map<String, OutboxRecord> working) {
        committedRows.clear();
        committedRows.putAll(working);
        leaseUntilByEventId.keySet().retainAll(working.keySet());
        notifyAll();
    }
}
