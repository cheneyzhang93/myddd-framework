package org.deep361.myddd.mq.kafka.consume;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.message.InboundMessage;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.BatchMessageListener;
import org.springframework.kafka.listener.ConsumerAwareMessageListener;
import org.springframework.kafka.support.Acknowledgment;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 监听器组（LM1–LM7）：真壳（registrar+dispatcher+plan）+ 真 {@link KafkaMessageListener}，
 * 投递用替身容器的 {@code deliver} 或直调 {@code onMessage}——一行 broker 都不碰（§0.3-7）。
 */
class KafkaMessageListenerTest {

    @Test
    void should_dispatch_a_delivered_record_to_the_business_method() {
        ConsumptionRig rig = ConsumptionRig.kafka("g");
        // WF 与 LM 的接缝在这条上闭合：读方向单独测到 map，本条测到 map 真的进了内核分发。
        new KafkaMessageListener(rig.dispatcher).onMessage(
                ConsumptionRig.wire("a-channel", "{\"id\":1}", Map.of("x-a", "1")));
        assertThat(rig.received).hasSize(1);
        InboundMessage message = rig.received.get(0);
        assertThat(message.channel()).isEqualTo("a-channel");
        assertThat(message.payload()).isEqualTo("{\"id\":1}");
        assertThat(message.messageId()).isEqualTo("a-channel-0-77");
        assertThat(message.headers()).containsExactly(Map.entry("x-a", "1"));
    }

    @Test
    void should_let_a_business_failure_escape_the_listener_untouched() {
        ConsumptionRig rig = ConsumptionRig.kafka("g");
        // 本模块最重要的一条：把它改成 catch(RuntimeException) 就等于静默跳过失败消息（容器照常提交 offset）。
        assertThatThrownBy(() -> new KafkaMessageListener(rig.dispatcher)
                .onMessage(ConsumptionRig.wire("boom", "p")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("业务失败");
        // 先记后抛 ⇒ 业务方法确实被调过一次。
        assertThat(rig.received).hasSize(1);
    }

    @Test
    void should_not_catch_an_error() {
        ConsumptionRig rig = ConsumptionRig.kafka("g");
        // Error 不捕获的唯一钉子：catch(Throwable) 的变异在此必死（厂商 handler 入参是 Exception，Error 不归它管）。
        assertThatThrownBy(() -> new KafkaMessageListener(rig.dispatcher)
                .onMessage(ConsumptionRig.wire("err", "p")))
                .isInstanceOf(StackOverflowError.class);
    }

    @Test
    void should_fail_a_tombstone_before_the_dispatcher_is_called() {
        ConsumptionRig rig = ConsumptionRig.kafka("g");
        // 翻译点在读方向、在分发之前：业务方法不该收到一条没有载荷的事件。
        assertThatThrownBy(() -> new KafkaMessageListener(rig.dispatcher)
                .onMessage(ConsumptionRig.wire("a-channel", null)))
                .isInstanceOf(FrameworkException.class);
        assertThat(rig.received).isEmpty();
    }

    @Test
    void should_be_stateless_under_concurrent_delivery() {
        ConsumptionRig rig = ConsumptionRig.kafka("g");
        KafkaMessageListener listener = new KafkaMessageListener(rig.dispatcher);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable deliverA = deliver(listener, "a-channel", ready, go, failure);
        Runnable deliverB = deliver(listener, "b-channel", ready, go, failure);
        Thread first = new Thread(deliverA);
        Thread second = new Thread(deliverB);
        first.start();
        second.start();
        awaitReady(ready);
        go.countDown();
        join(first);
        join(second);
        assertThat(failure.get()).isNull();
        assertThat(rig.received).hasSize(2);
    }

    @Test
    void should_not_claim_the_ack_or_consumer_handle() {
        ConsumptionRig rig = ConsumptionRig.kafka("g");
        KafkaMessageListener listener = new KafkaMessageListener(rig.dispatcher);
        // ①②钉"不接管提交/不接管批量"由类型保证。
        assertThat(listener).isNotInstanceOf(ConsumerAwareMessageListener.class);
        assertThat(listener).isNotInstanceOf(BatchMessageListener.class);
        // ③万一厂商真调了两个 default 重载，看到的是厂商自己的那句而不是静默半提交。
        ConsumerRecord<Object, Object> record = ConsumptionRig.wire("a-channel", "p");
        assertThatThrownBy(() -> listener.onMessage(record, (Acknowledgment) null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Container should never call this");
        assertThatThrownBy(() -> listener.onMessage(record, (Consumer<?, ?>) null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Container should never call this");
    }

    @Test
    void should_reject_a_missing_dispatcher() {
        // 只有一个参数：本类不拿组名/通道名做日志（与 Task 36 的 LM8 不同），所以没有 consumerGroup。
        assertThatThrownBy(() -> new KafkaMessageListener(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("dispatcher 不能为 null");
    }

    private static Runnable deliver(KafkaMessageListener listener, String channel, CountDownLatch ready,
                                    CountDownLatch go, AtomicReference<Throwable> failure) {
        return () -> {
            try {
                ready.countDown();
                go.await();
                listener.onMessage(ConsumptionRig.wire(channel, "p"));
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        };
    }

    private static void awaitReady(CountDownLatch ready) {
        try {
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(5_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
