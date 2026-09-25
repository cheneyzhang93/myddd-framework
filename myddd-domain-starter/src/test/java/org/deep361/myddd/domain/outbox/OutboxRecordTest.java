package org.deep361.myddd.domain.outbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxRecordTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-25T00:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-09-25T00:00:01Z");
    private static final Instant NEXT_RETRY_AT = Instant.parse("2026-09-25T00:01:00Z");

    @Test
    void should_create_pending_record_with_initial_state() {
        OutboxRecord record = OutboxRecord.pending("e-1", "org.example.OrderPaidEvent", "org.example.order.Order",
                "o-1", OCCURRED_AT, "trace-1", "order-events", "{\"orderId\":\"o-1\"}", CREATED_AT);

        assertThat(record.eventId()).isEqualTo("e-1");
        assertThat(record.eventType()).isEqualTo("org.example.OrderPaidEvent");
        assertThat(record.aggregateType()).isEqualTo("org.example.order.Order");
        assertThat(record.aggregateId()).isEqualTo("o-1");
        assertThat(record.traceId()).isEqualTo("trace-1");
        assertThat(record.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(record.channel()).isEqualTo("order-events");
        assertThat(record.payload()).isEqualTo("{\"orderId\":\"o-1\"}");
        assertThat(record.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(record.retryCount()).isZero();
        assertThat(record.nextRetryAt()).isEqualTo(CREATED_AT);
        assertThat(record.lastError()).isNull();
        assertThat(record.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void should_allow_absent_trace_id() {
        assertThat(pending().traceId()).isNull();
    }

    @Test
    void should_reject_blank_required_text_fields() {
        assertThatThrownBy(() -> OutboxRecord.pending(" ", "t", "a", "i", OCCURRED_AT, null, "c", "p", CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("eventId 不能为空白");
        assertThatThrownBy(() -> OutboxRecord.pending("e", "", "a", "i", OCCURRED_AT, null, "c", "p", CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("eventType 不能为空白");
        assertThatThrownBy(() -> OutboxRecord.pending("e", "t", "  ", "i", OCCURRED_AT, null, "c", "p", CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("aggregateType 不能为空白");
        assertThatThrownBy(() -> OutboxRecord.pending("e", "t", "a", "", OCCURRED_AT, null, "c", "p", CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("aggregateId 不能为空白");
        assertThatThrownBy(() -> OutboxRecord.pending("e", "t", "a", "i", OCCURRED_AT, null, " ", "p", CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("channel 不能为空白");
        assertThatThrownBy(() -> OutboxRecord.pending("e", "t", "a", "i", OCCURRED_AT, null, "c", " ", CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("payload 不能为空白");
    }

    @Test
    void should_reject_null_required_fields() {
        assertThatThrownBy(() -> OutboxRecord.pending(null, "t", "a", "i", OCCURRED_AT, null, "c", "p", CREATED_AT))
                .isInstanceOf(NullPointerException.class).hasMessage("eventId 不能为 null");
        assertThatThrownBy(() -> OutboxRecord.pending("e", null, "a", "i", OCCURRED_AT, null, "c", "p", CREATED_AT))
                .isInstanceOf(NullPointerException.class).hasMessage("eventType 不能为 null");
        assertThatThrownBy(() -> OutboxRecord.pending("e", "t", null, "i", OCCURRED_AT, null, "c", "p", CREATED_AT))
                .isInstanceOf(NullPointerException.class).hasMessage("aggregateType 不能为 null");
        assertThatThrownBy(() -> OutboxRecord.pending("e", "t", "a", null, OCCURRED_AT, null, "c", "p", CREATED_AT))
                .isInstanceOf(NullPointerException.class).hasMessage("aggregateId 不能为 null");
        assertThatThrownBy(() -> OutboxRecord.pending("e", "t", "a", "i", null, null, "c", "p", CREATED_AT))
                .isInstanceOf(NullPointerException.class).hasMessage("occurredAt 不能为 null");
        assertThatThrownBy(() -> OutboxRecord.pending("e", "t", "a", "i", OCCURRED_AT, null, null, "p", CREATED_AT))
                .isInstanceOf(NullPointerException.class).hasMessage("channel 不能为 null");
        assertThatThrownBy(() -> OutboxRecord.pending("e", "t", "a", "i", OCCURRED_AT, null, "c", null, CREATED_AT))
                .isInstanceOf(NullPointerException.class).hasMessage("payload 不能为 null");
        assertThatThrownBy(() -> OutboxRecord.pending("e", "t", "a", "i", OCCURRED_AT, null, "c", "p", null))
                .isInstanceOf(NullPointerException.class).hasMessage("createdAt 不能为 null");
        assertThatThrownBy(() -> new OutboxRecord("e", "t", "a", "i", null, OCCURRED_AT, "c", "p", null, 0,
                NEXT_RETRY_AT, null, CREATED_AT))
                .isInstanceOf(NullPointerException.class).hasMessage("status 不能为 null");
        assertThatThrownBy(() -> new OutboxRecord("e", "t", "a", "i", null, OCCURRED_AT, "c", "p",
                OutboxStatus.PENDING, 0, null, null, CREATED_AT))
                .isInstanceOf(NullPointerException.class).hasMessage("nextRetryAt 不能为 null");
    }

    @Test
    void should_reject_negative_retry_count() {
        assertThatThrownBy(() -> new OutboxRecord("e", "t", "a", "i", null, OCCURRED_AT, "c", "p",
                OutboxStatus.PENDING, -1, NEXT_RETRY_AT, null, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("retryCount 不能为负数，实际 -1");
    }

    @Test
    void should_claim_pending_record_as_dispatching() {
        OutboxRecord dispatching = pending().dispatching();

        assertThat(dispatching.status()).isEqualTo(OutboxStatus.DISPATCHING);
        assertThat(dispatching.retryCount()).isZero();
        assertThat(dispatching.payload()).isEqualTo(pending().payload());
        assertThat(dispatching.nextRetryAt()).isEqualTo(CREATED_AT);
        assertThat(dispatching.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void should_reclaim_expired_lease_from_dispatching() {
        OutboxRecord reclaimed = pending().dispatching().dispatching();

        assertThat(reclaimed.status()).isEqualTo(OutboxStatus.DISPATCHING);
        assertThat(reclaimed.retryCount()).isZero();
        assertThat(reclaimed.nextRetryAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void should_reject_claim_from_terminal_status() {
        OutboxRecord dispatched = pending().dispatching().dispatched();

        assertThatThrownBy(dispatched::dispatching)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只有 PENDING/DISPATCHING 记录可以被认领");
    }

    @Test
    void should_mark_dispatched_and_clear_last_error() {
        OutboxRecord dispatched = pending().dispatching().retrying(NEXT_RETRY_AT, "boom").dispatching().dispatched();

        assertThat(dispatched.status()).isEqualTo(OutboxStatus.DISPATCHED);
        assertThat(dispatched.retryCount()).isEqualTo(1);
        assertThat(dispatched.lastError()).isNull();
    }

    @Test
    void should_requeue_with_backoff_and_incremented_retry_count() {
        OutboxRecord retrying = pending().dispatching().retrying(NEXT_RETRY_AT, "boom");

        assertThat(retrying.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(retrying.retryCount()).isEqualTo(1);
        assertThat(retrying.nextRetryAt()).isEqualTo(NEXT_RETRY_AT);
        assertThat(retrying.lastError()).isEqualTo("boom");
    }

    @Test
    void should_mark_dead_with_incremented_retry_count() {
        OutboxRecord dead = pending().dispatching().dead("boom");

        assertThat(dead.status()).isEqualTo(OutboxStatus.DEAD);
        assertThat(dead.retryCount()).isEqualTo(1);
        assertThat(dead.lastError()).isEqualTo("boom");
    }

    @Test
    void should_reject_retry_and_dead_from_non_dispatching_status() {
        assertThatThrownBy(() -> pending().retrying(NEXT_RETRY_AT, "boom"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只有 DISPATCHING 记录可以重试");
        assertThatThrownBy(() -> pending().dead("boom"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只有 DISPATCHING 记录可以标记死信");
    }

    @Test
    void should_reject_blank_failure_reason() {
        assertThatThrownBy(() -> pending().dispatching().retrying(NEXT_RETRY_AT, " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("lastError 不能为空白");
        assertThatThrownBy(() -> pending().dispatching().retrying(null, "boom"))
                .isInstanceOf(NullPointerException.class).hasMessage("nextRetryAt 不能为 null");
        assertThatThrownBy(() -> pending().dispatching().dead(null))
                .isInstanceOf(NullPointerException.class).hasMessage("lastError 不能为 null");
    }

    private static OutboxRecord pending() {
        return OutboxRecord.pending("e-1", "org.example.OrderPaidEvent", "org.example.order.Order", "o-1",
                OCCURRED_AT, null, "order-events", "{\"orderId\":\"o-1\"}", CREATED_AT);
    }
}
