package org.deep361.myddd.domain.outbox;

import org.deep361.myddd.domain.event.EventTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 测试替身 {@link TransactionAwareInMemoryOutboxStore} 的直接契约用例（规格审查 F1 + 质量审查 TD2/TD3）。
 *
 * <p><b>为什么单独钉</b>：Task 16 的模块覆盖率（JaCoCo 不统计测试源码）不反映该替身的行为缺口，但后续
 * Task 17 的投递器测试、Plan 3 的 JDBC 实现都以它的语义为参照物——替身自身行为错，会同时污染两边的结论。
 *
 * <p>覆盖五条契约：{@code save} 只接受 PENDING、同一 {@code eventId} 不得重复保存；{@code claimDispatchable}
 * 按 {@code createdAt}、{@code eventId} 升序原子认领并返回 DISPATCHING、{@code limit} 生效、租约未过期不可重复
 * 认领 / 过期后可重新认领（崩溃恢复）；{@code update} 未落库的 {@code eventId} 必须抛错；记录离开 DISPATCHING
 * 后租约被清除。事务感知（工作副本/回滚）语义已由 {@code OutboxRecorderTest} 与
 * {@code SpringDomainEventPublisherTest} 覆盖，本类只钉非事务路径。
 */
class TransactionAwareInMemoryOutboxStoreTest {

    private static final Instant T0 = Instant.parse("2026-09-25T07:00:00Z");

    private final TransactionAwareInMemoryOutboxStore store = new TransactionAwareInMemoryOutboxStore();

    @AfterEach
    void cleanup() {
        store.reset();
        EventTestSupport.resetTransactionState();
    }

    @Test
    void should_save_only_pending_records() {
        OutboxRecord pending = pending("e-1", T0);
        store.save(pending);
        assertThat(store.committedRecords()).containsExactly(pending);

        assertThatThrownBy(() -> store.save(pending("e-2", T0).dispatching()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PENDING")
                .hasMessageContaining("e-2");
        assertThatThrownBy(() -> store.save(pending("e-3", T0).dispatching().dispatched()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISPATCHED");

        assertThat(store.committedRecords()).containsExactly(pending);
    }

    @Test
    void should_reject_duplicate_event_id() {
        OutboxRecord first = pending("e-1", T0);
        store.save(first);

        assertThatThrownBy(() -> store.save(pending("e-1", T0.plusSeconds(1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("e-1");

        // 已存在的那条不被覆盖
        assertThat(store.committedRecords()).containsExactly(first);
    }

    @Test
    void should_claim_in_created_at_then_event_id_order_and_mark_dispatching() {
        store.save(pending("e-3", T0.plusSeconds(2)));
        store.save(pending("e-2", T0));
        store.save(pending("e-1", T0));

        // nextRetryAt = createdAt：now 取最大 createdAt，三条才都在可投递窗口内
        List<OutboxRecord> claimed = store.claimDispatchable(T0.plusSeconds(2), T0.plusSeconds(32), 10);

        assertThat(claimed).extracting(OutboxRecord::eventId).containsExactly("e-1", "e-2", "e-3");
        assertThat(claimed).extracting(OutboxRecord::status).containsOnly(OutboxStatus.DISPATCHING);
        assertThat(store.committedRecords()).extracting(OutboxRecord::status).containsOnly(OutboxStatus.DISPATCHING);
    }

    @Test
    void should_respect_claim_limit() {
        store.save(pending("e-1", T0));
        store.save(pending("e-2", T0.plusSeconds(1)));
        store.save(pending("e-3", T0.plusSeconds(2)));

        assertThat(store.claimDispatchable(T0.plusSeconds(2), T0.plusSeconds(32), 2))
                .extracting(OutboxRecord::eventId).containsExactly("e-1", "e-2");
        assertThat(store.committedRecords())
                .extracting(OutboxRecord::eventId, OutboxRecord::status)
                .containsExactly(tuple("e-1", OutboxStatus.DISPATCHING), tuple("e-2", OutboxStatus.DISPATCHING),
                        tuple("e-3", OutboxStatus.PENDING));

        // 未认领的记录不受租约影响，下一轮立刻可认领
        assertThat(store.claimDispatchable(T0.plusSeconds(2), T0.plusSeconds(32), 10))
                .extracting(OutboxRecord::eventId).containsExactly("e-3");
    }

    @Test
    void should_reject_non_positive_claim_limit() {
        assertThatThrownBy(() -> store.claimDispatchable(T0, T0.plusSeconds(30), 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> store.claimDispatchable(T0, T0.plusSeconds(30), -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit");
    }

    @Test
    void should_not_reclaim_with_active_lease_but_reclaim_after_expiry() {
        store.save(pending("e-1", T0));
        store.claimDispatchable(T0, T0.plusSeconds(30), 10);

        // 租约未过期（含到期前一刻）不可重复认领
        assertThat(store.claimDispatchable(T0.plusSeconds(29), T0.plusSeconds(59), 10)).isEmpty();

        // 租约到期即视为"投递器崩溃"，可重新认领，且认领即续租
        assertThat(store.claimDispatchable(T0.plusSeconds(30), T0.plusSeconds(60), 10))
                .extracting(OutboxRecord::eventId, OutboxRecord::status)
                .containsExactly(tuple("e-1", OutboxStatus.DISPATCHING));
        assertThat(store.claimDispatchable(T0.plusSeconds(31), T0.plusSeconds(60), 10)).isEmpty();
    }

    @Test
    void should_reject_update_for_unknown_event_id() {
        assertThatThrownBy(() -> store.update(pending("e-404", T0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("e-404");

        assertThat(store.committedRecords()).isEmpty();
    }

    /**
     * 记录离开 DISPATCHING（如 {@code retrying} 回到 PENDING）后，旧租约必须被清除。
     *
     * <p>租约表是替身的私有状态，公开 API 上唯一可观测的形态是：<b>没有租约记录的 DISPATCHING 记录立即可被
     * 认领</b>（{@code leaseUntil == null} 视为已过期）。因此本用例先让记录经"认领 → 失败回 PENDING（清租约）"，
     * 再把它（合法迁移 PENDING → DISPATCHING）落库，最后在旧租约 T0+30 到期<b>之前</b>的 T0+10 认领：租约被清除
     * 才能认领成功；若 {@code update} 漏清租约，旧租约会把这次认领挡回去（返回空）。
     */
    @Test
    void should_clear_lease_when_record_leaves_dispatching() {
        store.save(pending("e-1", T0));
        OutboxRecord claimed = store.claimDispatchable(T0, T0.plusSeconds(30), 10).get(0);

        store.update(claimed.retrying(T0.plusSeconds(5), "boom"));
        store.update(store.committedRecords().get(0).dispatching());

        assertThat(store.claimDispatchable(T0.plusSeconds(10), T0.plusSeconds(40), 10))
                .extracting(OutboxRecord::eventId).containsExactly("e-1");
    }

    @Test
    void should_await_status_until_reached_and_report_timeout() throws InterruptedException {
        store.save(pending("e-1", T0));

        // 已在期望状态：立即返回 true
        assertThat(store.awaitStatus("e-1", OutboxStatus.PENDING, Duration.ofSeconds(1))).isTrue();

        // 未达期望状态 / 记录不存在：到点返回 false（不假通过）
        assertThat(store.awaitStatus("e-1", OutboxStatus.DISPATCHED, Duration.ofMillis(50))).isFalse();
        assertThat(store.awaitStatus("e-404", OutboxStatus.PENDING, Duration.ofMillis(50))).isFalse();
    }

    /**
     * 唤醒路径的确定性钉子：等到等待线程<b>确实阻塞在 store 监视器上</b>（{@code TIMED_WAITING}）之后再推进记录
     * 状态，此时只有等待/通知能把它唤醒——若 store 漏掉 {@code notifyAll()}，等待线程只能干等到超时并返回
     * {@code false}（等待上限 30s 远大于 5s 的 join 预算，突变体必然被杀）。
     */
    @Test
    void should_wake_waiter_when_record_reaches_expected_status() throws InterruptedException {
        store.save(pending("e-1", T0));
        AtomicBoolean reached = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            try {
                reached.set(store.awaitStatus("e-1", OutboxStatus.DISPATCHED, Duration.ofSeconds(30)));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }, "store-await-probe");
        waiter.setDaemon(true);

        waiter.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (waiter.getState() != Thread.State.TIMED_WAITING) {
            assertThat(System.nanoTime()).as("等待线程应在 5s 内阻塞在 store 监视器上").isLessThan(deadline);
            Thread.onSpinWait();
        }

        // 只做一次状态变化（不经 claimDispatchable，避免其 notifyAll 混淆唤醒来源）
        store.update(pending("e-1", T0).dispatching().dispatched());
        waiter.join(Duration.ofSeconds(5).toMillis());
        boolean wokenUp = !waiter.isAlive();
        waiter.interrupt(); // 兜底：若未唤醒（缺陷）也不让线程干等到 30s 超时

        assertThat(wokenUp).as("awaitStatus 应被状态变化的通知唤醒，而不是等到超时").isTrue();
        assertThat(reached).isTrue();
    }

    @Test
    void should_reject_null_arguments() {
        assertThatThrownBy(() -> store.save(null))
                .isInstanceOf(NullPointerException.class).hasMessage("record 不能为 null");
        assertThatThrownBy(() -> store.claimDispatchable(null, T0, 1))
                .isInstanceOf(NullPointerException.class).hasMessage("now 不能为 null");
        assertThatThrownBy(() -> store.claimDispatchable(T0, null, 1))
                .isInstanceOf(NullPointerException.class).hasMessage("leaseUntil 不能为 null");
        assertThatThrownBy(() -> store.update(null))
                .isInstanceOf(NullPointerException.class).hasMessage("record 不能为 null");
        assertThatThrownBy(() -> store.awaitStatus(null, OutboxStatus.PENDING, Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class).hasMessage("eventId 不能为 null");
        assertThatThrownBy(() -> store.awaitStatus("e-1", null, Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class).hasMessage("status 不能为 null");
        assertThatThrownBy(() -> store.awaitStatus("e-1", OutboxStatus.PENDING, null))
                .isInstanceOf(NullPointerException.class).hasMessage("timeout 不能为 null");
    }

    private static OutboxRecord pending(String eventId, Instant createdAt) {
        return OutboxRecord.pending(eventId, "org.example.OrderPaidEvent", "org.example.order.Order", "o-1",
                createdAt, null, "order-events", "{\"eventId\":\"" + eventId + "\"}", createdAt);
    }
}
