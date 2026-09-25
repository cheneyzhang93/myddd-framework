package org.deep361.myddd.domain.outbox;

import org.deep361.myddd.domain.event.EventTestSupport;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.common.trace.TraceContext;
import org.deep361.myddd.kernel.ddd.event.DomainEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxRecorderTest {

    private static final Instant NOW = Instant.parse("2026-09-25T06:00:00Z");
    private static final String AGGREGATE_TYPE = "org.example.order.Order";

    private final TransactionAwareInMemoryOutboxStore store = new TransactionAwareInMemoryOutboxStore();
    private final OutboxRecorder recorder = new OutboxRecorder(store, new JacksonOutboxPayloadSerializer(),
            OutboxChannelResolver.byAggregateType(), Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void cleanup() {
        TraceContext.clear();
        EventTestSupport.resetTransactionState();
        store.reset();
    }

    @Test
    void should_record_pending_row_with_resolved_channel_and_trace() {
        TraceContext.set("trace-1");
        EventTestSupport.beginTransaction();

        OutboxRecord record = recorder.record(OutboxPayloadFixtures.ORDER_PAID, AGGREGATE_TYPE, "o-1");

        assertThat(record.eventId()).isEqualTo(OutboxPayloadFixtures.EVENT_ID);
        assertThat(record.eventType()).isEqualTo(OutboxPayloadFixtures.OrderPaidEvent.class.getName());
        assertThat(record.aggregateType()).isEqualTo(AGGREGATE_TYPE);
        assertThat(record.aggregateId()).isEqualTo("o-1");
        assertThat(record.occurredAt()).isEqualTo(OutboxPayloadFixtures.OCCURRED_AT);
        assertThat(record.traceId()).isEqualTo("trace-1");
        assertThat(record.channel()).isEqualTo("order-events");
        assertThat(record.payload()).isEqualTo(OutboxPayloadFixtures.ORDER_PAID_JSON);
        assertThat(record.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(record.retryCount()).isZero();
        assertThat(record.nextRetryAt()).isEqualTo(NOW);
        assertThat(record.createdAt()).isEqualTo(NOW);

        EventTestSupport.commitTransaction();

        assertThat(store.committedRecords()).containsExactly(record);
    }

    @Test
    void should_allow_absent_trace_id() {
        EventTestSupport.beginTransaction();

        assertThat(recorder.record(OutboxPayloadFixtures.ORDER_PAID, AGGREGATE_TYPE, "o-1").traceId()).isNull();
    }

    @Test
    void should_reject_recording_without_active_transaction() {
        assertThatThrownBy(() -> recorder.record(OutboxPayloadFixtures.ORDER_PAID, AGGREGATE_TYPE, "o-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Outbox 记录必须在事务内执行");
        assertThat(store.committedRecords()).isEmpty();
    }

    @Test
    void should_reject_blank_channel_returned_by_resolver() {
        OutboxRecorder blankChannelRecorder = new OutboxRecorder(store, new JacksonOutboxPayloadSerializer(),
                (event, aggregateType) -> "  ", Clock.fixed(NOW, ZoneOffset.UTC));
        OutboxRecorder nullChannelRecorder = new OutboxRecorder(store, new JacksonOutboxPayloadSerializer(),
                (event, aggregateType) -> null, Clock.fixed(NOW, ZoneOffset.UTC));
        EventTestSupport.beginTransaction();

        assertThatThrownBy(() -> blankChannelRecorder.record(OutboxPayloadFixtures.ORDER_PAID, AGGREGATE_TYPE, "o-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OutboxChannelResolver 返回了空白通道");
        assertThatThrownBy(() -> nullChannelRecorder.record(OutboxPayloadFixtures.ORDER_PAID, AGGREGATE_TYPE, "o-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OutboxChannelResolver 返回了空白通道");
        assertThat(store.committedRecords()).isEmpty();
    }

    @Test
    void should_propagate_serialization_failure_and_not_save() {
        OutboxRecorder failingSerializerRecorder = new OutboxRecorder(store, new FailingSerializer(),
                OutboxChannelResolver.byAggregateType(), Clock.fixed(NOW, ZoneOffset.UTC));
        EventTestSupport.beginTransaction();

        assertThatThrownBy(() -> failingSerializerRecorder.record(OutboxPayloadFixtures.ORDER_PAID, AGGREGATE_TYPE, "o-1"))
                .isInstanceOf(FrameworkException.class).hasMessage("序列化炸了");
        assertThat(store.committedRecords()).isEmpty();
    }

    @Test
    void should_propagate_store_failure_so_business_transaction_rolls_back() {
        OutboxStore failingStore = new OutboxStore() {
            @Override
            public void save(OutboxRecord record) {
                throw new IllegalStateException("store 炸了");
            }

            @Override
            public List<OutboxRecord> claimDispatchable(Instant now, Instant leaseUntil, int limit) {
                throw new UnsupportedOperationException("测试替身不实现认领");
            }

            @Override
            public void update(OutboxRecord record) {
                throw new UnsupportedOperationException("测试替身不实现更新");
            }
        };
        OutboxRecorder failingStoreRecorder = new OutboxRecorder(failingStore, new JacksonOutboxPayloadSerializer(),
                OutboxChannelResolver.byAggregateType(), Clock.fixed(NOW, ZoneOffset.UTC));
        EventTestSupport.beginTransaction();

        assertThatThrownBy(() -> failingStoreRecorder.record(OutboxPayloadFixtures.ORDER_PAID, AGGREGATE_TYPE, "o-1"))
                .isInstanceOf(IllegalStateException.class).hasMessage("store 炸了");
    }

    @Test
    void should_discard_record_when_transaction_rolls_back() {
        EventTestSupport.beginTransaction();
        recorder.record(OutboxPayloadFixtures.ORDER_PAID, AGGREGATE_TYPE, "o-1");
        EventTestSupport.rollbackTransaction();

        assertThat(store.committedRecords()).isEmpty();

        EventTestSupport.beginTransaction();
        recorder.record(OutboxPayloadFixtures.ORDER_PAID, AGGREGATE_TYPE, "o-1");
        EventTestSupport.commitTransaction();

        assertThat(store.committedRecords()).hasSize(1);
    }

    @Test
    void should_reject_null_arguments() {
        EventTestSupport.beginTransaction();

        assertThatThrownBy(() -> recorder.record(null, AGGREGATE_TYPE, "o-1"))
                .isInstanceOf(NullPointerException.class).hasMessage("event 不能为 null");
        assertThatThrownBy(() -> recorder.record(OutboxPayloadFixtures.ORDER_PAID, null, "o-1"))
                .isInstanceOf(NullPointerException.class).hasMessage("aggregateType 不能为 null");
        assertThatThrownBy(() -> recorder.record(OutboxPayloadFixtures.ORDER_PAID, AGGREGATE_TYPE, null))
                .isInstanceOf(NullPointerException.class).hasMessage("aggregateId 不能为 null");
    }

    /** 序列化即失败的载荷序列化器（{@link OutboxPayloadSerializer} 有两个方法，不能用 lambda）。 */
    private static final class FailingSerializer implements OutboxPayloadSerializer {

        @Override
        public String serialize(DomainEvent event) {
            throw new FrameworkException("序列化炸了");
        }

        @Override
        public <T extends DomainEvent> T deserialize(String payload, Class<T> eventType) {
            throw new UnsupportedOperationException("测试替身不实现反序列化");
        }
    }
}
