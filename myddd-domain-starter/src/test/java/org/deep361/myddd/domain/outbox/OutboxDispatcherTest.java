package org.deep361.myddd.domain.outbox;

import org.deep361.myddd.kernel.port.message.MessageHeaderNames;
import org.deep361.myddd.kernel.port.message.OutboundMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class OutboxDispatcherTest {

    private static final Instant T0 = Instant.parse("2026-09-25T07:00:00Z");

    private final TransactionAwareInMemoryOutboxStore store = new TransactionAwareInMemoryOutboxStore();
    private final RecordingMessagePort port = new RecordingMessagePort();
    private final MutableClock clock = new MutableClock(T0);

    @AfterEach
    void cleanup() {
        store.reset();
    }

    @Test
    void should_dispatch_claimed_records_in_order_and_mark_dispatched() {
        savedRecord("e-1", T0);
        savedRecord("e-2", T0.plusSeconds(1));
        // e-2 的 nextRetryAt = createdAt = T0+1：按存储契约"nextRetryAt 之前不可投递"，时钟须先走到 T0+1
        clock.advance(Duration.ofSeconds(1));

        OutboxDispatcher.DispatchResult result = dispatcher(3).dispatchOnce();

        assertThat(result.claimed()).isEqualTo(2);
        assertThat(result.dispatched()).isEqualTo(2);
        assertThat(port.sent()).extracting(OutboundMessage::payload)
                .containsExactly("{\"eventId\":\"e-1\"}", "{\"eventId\":\"e-2\"}");
        assertThat(store.committedRecords()).extracting(OutboxRecord::status)
                .containsExactly(OutboxStatus.DISPATCHED, OutboxStatus.DISPATCHED);
    }

    @Test
    void should_write_event_headers_and_aggregate_key_on_message() {
        savedRecord("e-1", T0);

        dispatcher(3).dispatchOnce();

        OutboundMessage message = port.sent().get(0);
        assertThat(message.channel()).isEqualTo("order-events");
        assertThat(message.key()).isEqualTo("o-1");
        assertThat(message.header(MessageHeaderNames.EVENT_ID)).contains("e-1");
        assertThat(message.header(MessageHeaderNames.EVENT_TYPE)).contains("org.example.OrderPaidEvent");
        assertThat(message.header(MessageHeaderNames.TRACE_ID)).contains("trace-1");
        assertThat(message.deliverAt()).isNull();
    }

    @Test
    void should_omit_trace_header_when_record_has_no_trace_id() {
        store.save(OutboxRecord.pending("e-1", "org.example.OrderPaidEvent", "org.example.order.Order", "o-1",
                T0, null, "order-events", "{}", T0));

        dispatcher(3).dispatchOnce();

        assertThat(port.sent().get(0).header(MessageHeaderNames.TRACE_ID)).isEmpty();
    }

    @Test
    void should_not_claim_record_scheduled_in_the_future() {
        store.update(savedRecord("e-1", T0).dispatching().retrying(T0.plusSeconds(3600), "boom"));

        OutboxDispatcher.DispatchResult result = dispatcher(3).dispatchOnce();

        assertThat(result.claimed()).isZero();
        assertThat(port.sent()).isEmpty();
    }

    @Test
    void should_retry_with_backoff_and_increment_failure_count() {
        savedRecord("e-1", T0);
        port.failNext(Integer.MAX_VALUE);

        OutboxDispatcher.DispatchResult result = dispatcher(3).dispatchOnce();

        assertThat(result.retried()).isEqualTo(1);
        assertThat(port.sent()).isEmpty();
        OutboxRecord record = store.committedRecords().get(0);
        assertThat(record.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(record.retryCount()).isEqualTo(1);
        assertThat(record.nextRetryAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(record.lastError()).contains("message port 炸了");
    }

    @Test
    void should_not_redeliver_before_next_retry_time() {
        savedRecord("e-1", T0);
        port.failNext(Integer.MAX_VALUE);
        OutboxDispatcher dispatcher = dispatcher(3);
        dispatcher.dispatchOnce();

        assertThat(dispatcher.dispatchOnce().claimed()).isZero();
    }

    @Test
    void should_redeliver_after_backoff_delay() {
        savedRecord("e-1", T0);
        port.failNext(1);
        OutboxDispatcher dispatcher = dispatcher(3);
        dispatcher.dispatchOnce();

        clock.advance(Duration.ofSeconds(6));
        OutboxDispatcher.DispatchResult second = dispatcher.dispatchOnce();

        assertThat(second.dispatched()).isEqualTo(1);
        OutboxRecord record = store.committedRecords().get(0);
        assertThat(record.status()).isEqualTo(OutboxStatus.DISPATCHED);
        assertThat(record.retryCount()).isEqualTo(1);
        assertThat(record.lastError()).isNull();
    }

    @Test
    void should_mark_dead_after_max_attempts() {
        savedRecord("e-1", T0);
        port.failNext(Integer.MAX_VALUE);
        OutboxDispatcher dispatcher = dispatcher(2);

        dispatcher.dispatchOnce();
        clock.advance(Duration.ofSeconds(6));
        OutboxDispatcher.DispatchResult second = dispatcher.dispatchOnce();

        assertThat(second.dead()).isEqualTo(1);
        OutboxRecord record = store.committedRecords().get(0);
        assertThat(record.status()).isEqualTo(OutboxStatus.DEAD);
        assertThat(record.retryCount()).isEqualTo(2);
        assertThat(record.lastError()).contains("message port 炸了");

        clock.advance(Duration.ofHours(1));
        assertThat(dispatcher.dispatchOnce().claimed()).isZero();
    }

    @Test
    void should_continue_batch_when_one_record_fails() {
        savedRecord("e-1", T0);
        savedRecord("e-2", T0.plusSeconds(1));
        clock.advance(Duration.ofSeconds(1));
        port.failNext(1);

        OutboxDispatcher.DispatchResult result = dispatcher(3).dispatchOnce();

        assertThat(result.claimed()).isEqualTo(2);
        assertThat(result.retried()).isEqualTo(1);
        assertThat(result.dispatched()).isEqualTo(1);
        assertThat(store.committedRecords())
                .extracting(OutboxRecord::eventId, OutboxRecord::status)
                .containsExactly(tuple("e-1", OutboxStatus.PENDING), tuple("e-2", OutboxStatus.DISPATCHED));
    }

    @Test
    void should_truncate_long_error_message() {
        savedRecord("e-1", T0);
        port.failureMessage("x".repeat(1000));
        port.failNext(Integer.MAX_VALUE);

        dispatcher(3).dispatchOnce();

        assertThat(store.committedRecords().get(0).lastError()).hasSize(500);
    }

    @Test
    void should_reclaim_record_after_lease_expiry() {
        savedRecord("e-1", T0);
        store.claimDispatchable(T0, T0.plusSeconds(30), 10);

        assertThat(dispatcher(3).dispatchOnce().claimed()).isZero();

        clock.advance(Duration.ofSeconds(31));
        assertThat(dispatcher(3).dispatchOnce().dispatched()).isEqualTo(1);
    }

    @Test
    void should_return_zero_result_when_nothing_claimable() {
        OutboxDispatcher.DispatchResult result = dispatcher(3).dispatchOnce();

        assertThat(result).isEqualTo(new OutboxDispatcher.DispatchResult(0, 0, 0, 0));
        assertThat(port.sent()).isEmpty();
    }

    @Test
    void should_propagate_store_update_failure() {
        OutboxStore failingUpdateStore = new OutboxStore() {
            @Override
            public void save(OutboxRecord record) {
                store.save(record);
            }

            @Override
            public List<OutboxRecord> claimDispatchable(Instant now, Instant leaseUntil, int limit) {
                return store.claimDispatchable(now, leaseUntil, limit);
            }

            @Override
            public void update(OutboxRecord record) {
                throw new IllegalStateException("update 炸了");
            }
        };
        savedRecord("e-1", T0);
        OutboxDispatcher dispatcher = new OutboxDispatcher(port, failingUpdateStore,
                OutboxBackoff.fixed(Duration.ofSeconds(5)), OutboxDispatchOptions.defaults(), clock);

        assertThatThrownBy(dispatcher::dispatchOnce)
                .isInstanceOf(IllegalStateException.class).hasMessage("update 炸了");
        assertThat(port.sent()).hasSize(1);
    }

    @Test
    void should_reject_null_collaborators() {
        OutboxDispatchOptions options = OutboxDispatchOptions.defaults();
        OutboxBackoff backoff = OutboxBackoff.fixed(Duration.ofSeconds(5));

        assertThatThrownBy(() -> new OutboxDispatcher(null, store, backoff, options, clock))
                .isInstanceOf(NullPointerException.class).hasMessage("MessagePort 不能为 null");
        assertThatThrownBy(() -> new OutboxDispatcher(port, null, backoff, options, clock))
                .isInstanceOf(NullPointerException.class).hasMessage("OutboxStore 不能为 null");
        assertThatThrownBy(() -> new OutboxDispatcher(port, store, null, options, clock))
                .isInstanceOf(NullPointerException.class).hasMessage("OutboxBackoff 不能为 null");
        assertThatThrownBy(() -> new OutboxDispatcher(port, store, backoff, null, clock))
                .isInstanceOf(NullPointerException.class).hasMessage("OutboxDispatchOptions 不能为 null");
        assertThatThrownBy(() -> new OutboxDispatcher(port, store, backoff, options, null))
                .isInstanceOf(NullPointerException.class).hasMessage("Clock 不能为 null");
    }

    private OutboxDispatcher dispatcher(int maxAttempts) {
        return new OutboxDispatcher(port, store, OutboxBackoff.fixed(Duration.ofSeconds(5)),
                new OutboxDispatchOptions(maxAttempts, 10, Duration.ofSeconds(30)), clock);
    }

    private OutboxRecord savedRecord(String eventId, Instant createdAt) {
        OutboxRecord record = OutboxRecord.pending(eventId, "org.example.OrderPaidEvent", "org.example.order.Order",
                "o-1", createdAt, "trace-1", "order-events", "{\"eventId\":\"" + eventId + "\"}", createdAt);
        store.save(record);
        return record;
    }
}
