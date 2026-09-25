package org.deep361.myddd.domain.outbox;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxDispatchLoopTest {

    private static final Duration WAIT_UPPER_BOUND = Duration.ofSeconds(2);

    @Test
    void should_not_claim_before_first_poll_interval_elapsed() throws InterruptedException {
        CountingOutboxStore store = new CountingOutboxStore();
        OutboxDispatchLoop loop = loop(store, Duration.ofHours(1), Duration.ofSeconds(1));
        try {
            loop.start();

            Thread.sleep(100);

            assertThat(store.claimCalls()).as("首次取件必须等满一个轮询间隔，实际已取件 %d 次", store.claimCalls()).isZero();
        } finally {
            loop.stop();
        }
    }

    @Test
    void should_stop_polling_after_stop() throws InterruptedException {
        CountingOutboxStore store = new CountingOutboxStore();
        OutboxDispatchLoop loop = loop(store, Duration.ofMillis(25), Duration.ofMillis(500));
        try {
            loop.start();
            assertThat(awaitClaimCalls(store, 2, WAIT_UPPER_BOUND))
                    .as("轮询未在 2s 内跑满 2 轮：claimCalls=%d", store.claimCalls()).isTrue();

            loop.stop();
            int settled = store.claimCalls();

            Thread.sleep(150);

            assertThat(store.claimCalls())
                    .as("stop() 后仍在取件：%d → %d", settled, store.claimCalls()).isEqualTo(settled);
        } finally {
            loop.stop();
        }
    }

    @Test
    void should_ignore_second_start_and_stop_all_polling() throws InterruptedException {
        CountingOutboxStore store = new CountingOutboxStore();
        OutboxDispatchLoop loop = loop(store, Duration.ofMillis(25), Duration.ofMillis(500));
        try {
            loop.start();
            loop.start();
            assertThat(awaitClaimCalls(store, 2, WAIT_UPPER_BOUND))
                    .as("轮询未在 2s 内跑满 2 轮：claimCalls=%d", store.claimCalls()).isTrue();

            loop.stop();
            int settled = store.claimCalls();

            Thread.sleep(150);

            assertThat(store.claimCalls())
                    .as("第二次 start() 产生的调度器未被 stop() 停掉：%d → %d", settled, store.claimCalls())
                    .isEqualTo(settled);
        } finally {
            loop.stop();
        }
    }

    @Test
    void should_reject_poll_interval_truncating_to_zero_millis() {
        OutboxDispatcher dispatcher = dispatcher(new CountingOutboxStore());

        assertThatThrownBy(() -> new OutboxDispatchLoop(dispatcher, Duration.ofNanos(999_999), Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pollInterval");
    }

    @Test
    void should_reject_negative_shutdown_timeout() {
        OutboxDispatcher dispatcher = dispatcher(new CountingOutboxStore());

        assertThatThrownBy(() -> new OutboxDispatchLoop(dispatcher, Duration.ofSeconds(1), Duration.ofNanos(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shutdownTimeout");
    }

    @Test
    void should_keep_polling_after_round_failure() throws InterruptedException {
        CountingOutboxStore store = new CountingOutboxStore(2);
        OutboxDispatchLoop loop = loop(store, Duration.ofMillis(25), Duration.ofSeconds(1));
        try {
            loop.start();

            assertThat(awaitClaimCalls(store, 4, WAIT_UPPER_BOUND))
                    .as("异常逃逸导致循环停摆：claimCalls=%d", store.claimCalls()).isTrue();
        } finally {
            loop.stop();
        }
    }

    private static OutboxDispatchLoop loop(OutboxStore store, Duration pollInterval, Duration shutdownTimeout) {
        return new OutboxDispatchLoop(dispatcher(store), pollInterval, shutdownTimeout);
    }

    private static OutboxDispatcher dispatcher(OutboxStore store) {
        return new OutboxDispatcher(message -> {
        }, store, OutboxBackoff.fixed(Duration.ofSeconds(5)), OutboxDispatchOptions.defaults(), Clock.systemUTC());
    }

    private static boolean awaitClaimCalls(CountingOutboxStore store, int expected, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (store.claimCalls() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return store.claimCalls() >= expected;
    }
}
