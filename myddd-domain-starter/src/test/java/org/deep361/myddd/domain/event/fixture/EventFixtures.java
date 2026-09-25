package org.deep361.myddd.domain.event.fixture;

import org.deep361.myddd.kernel.ddd.event.DomainEvent;
import org.deep361.myddd.kernel.ddd.event.DomainEventListener;
import org.deep361.myddd.kernel.ddd.event.DomainEventStage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 事件总线测试夹具：事件、录制器与各场景监听器 bean。 */
public final class EventFixtures {

    private EventFixtures() {
    }

    /** 测试事件：订单已支付。 */
    public record OrderPaidEvent(String eventId, Instant occurredAt, String orderId) implements DomainEvent {
    }

    /** 测试事件：订单已发货。 */
    public record OrderShippedEvent(String eventId, Instant occurredAt, String orderId) implements DomainEvent {
    }

    /** 线程安全的调用录制器：记录调用名、事件对象与调用线程名（异步断言需要线程名证据）。 */
    public static final class EventRecorder {

        private final List<String> names = new ArrayList<>();
        private final List<DomainEvent> events = new ArrayList<>();
        private final List<String> threads = new ArrayList<>();

        public synchronized void record(String name, DomainEvent event) {
            names.add(name);
            events.add(event);
            threads.add(Thread.currentThread().getName());
            notifyAll();
        }

        public synchronized List<String> names() {
            return List.copyOf(names);
        }

        public synchronized List<DomainEvent> events() {
            return List.copyOf(events);
        }

        public synchronized List<String> threads() {
            return List.copyOf(threads);
        }

        /** 等待录制条数达到期望值（异步投递断用；不用轮询）。 */
        public synchronized boolean awaitCount(int expected, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (names.size() < expected) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            return true;
        }
    }

    /** 事务内监听订单支付事件。 */
    public static class PaidListenerBean {

        private final EventRecorder recorder;

        public PaidListenerBean(EventRecorder recorder) {
            this.recorder = recorder;
        }

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION)
        public void on(OrderPaidEvent event) {
            recorder.record("paid", event);
        }
    }

    /** 事务内只监听发货事件（验证按参数类型匹配，不接收支付事件）。 */
    public static class ShippedListenerBean {

        private final EventRecorder recorder;

        public ShippedListenerBean(EventRecorder recorder) {
            this.recorder = recorder;
        }

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION)
        public void on(OrderShippedEvent event) {
            recorder.record("shipped", event);
        }
    }

    /** 事务内接收全部事件（参数类型为 DomainEvent 本身）。 */
    public static class AnyEventListenerBean {

        private final EventRecorder recorder;

        public AnyEventListenerBean(EventRecorder recorder) {
            this.recorder = recorder;
        }

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION)
        public void on(DomainEvent event) {
            recorder.record("any", event);
        }
    }

    /** 事务内、order = -10（排序断言用）。 */
    public static class HighOrderListenerBean {

        private final EventRecorder recorder;

        public HighOrderListenerBean(EventRecorder recorder) {
            this.recorder = recorder;
        }

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION, order = -10)
        public void on(OrderPaidEvent event) {
            recorder.record("high", event);
        }
    }

    /** 事务内、order = 10（排序断言用）。 */
    public static class LowOrderListenerBean {

        private final EventRecorder recorder;

        public LowOrderListenerBean(EventRecorder recorder) {
            this.recorder = recorder;
        }

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION, order = 10)
        public void on(OrderPaidEvent event) {
            recorder.record("low", event);
        }
    }

    /** 未声明 stage：默认 AFTER_COMMIT。 */
    public static class DefaultStageListenerBean {

        private final EventRecorder recorder;

        public DefaultStageListenerBean(EventRecorder recorder) {
            this.recorder = recorder;
        }

        @DomainEventListener
        public void on(OrderPaidEvent event) {
            recorder.record("after-commit", event);
        }
    }

    /** 异步监听（stage 默认 AFTER_COMMIT）、order = 5。 */
    public static class AsyncListenerBean {

        private final EventRecorder recorder;

        public AsyncListenerBean(EventRecorder recorder) {
            this.recorder = recorder;
        }

        @DomainEventListener(async = true, order = 5)
        public void on(OrderPaidEvent event) {
            recorder.record("async", event);
        }
    }

    /** 异步慢监听器：在工作线程里停留一段可观测时间（关闭时等待在飞任务的断言用）。 */
    public static class SlowAsyncListenerBean {

        /** 工作线程已开始执行本监听器。 */
        public final CountDownLatch started = new CountDownLatch(1);

        private final EventRecorder recorder;
        private final AtomicBoolean finished = new AtomicBoolean();

        public SlowAsyncListenerBean(EventRecorder recorder) {
            this.recorder = recorder;
        }

        @DomainEventListener(async = true)
        public void on(OrderPaidEvent event) {
            started.countDown();
            try {
                Thread.sleep(300);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            finished.set(true);
            recorder.record("slow", event);
        }

        public boolean finished() {
            return finished.get();
        }
    }

    /**
     * 同 stage、同 order 的 4 个方法：声明顺序被人为打乱（zeta → on(DomainEvent) → on(OrderPaidEvent) → alpha），
     * 期望顺序只由 方法名 → 方法签名 决定，不依赖反射返回顺序。
     */
    public static class NameOrderListenerBean {

        private final EventRecorder recorder;

        public NameOrderListenerBean(EventRecorder recorder) {
            this.recorder = recorder;
        }

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION, order = 7)
        public void zeta(OrderPaidEvent event) {
            recorder.record("zeta", event);
        }

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION, order = 7)
        public void on(DomainEvent event) {
            recorder.record("any", event);
        }

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION, order = 7)
        public void on(OrderPaidEvent event) {
            recorder.record("paid", event);
        }

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION, order = 7)
        public void alpha(OrderPaidEvent event) {
            recorder.record("alpha", event);
        }
    }

    /** 无注解方法：不应被注册。 */
    public static class PlainBean {

        public void on(OrderPaidEvent event) {
            throw new AssertionError("无注解的方法不应被注册或调用");
        }
    }

    /** 注解声明在接口方法上。 */
    public interface ContractListener {

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION)
        void handle(OrderPaidEvent event);
    }

    /** 接口实现类自身不重复标注。 */
    public static class ContractListenerBean implements ContractListener {

        private final EventRecorder recorder;

        public ContractListenerBean(EventRecorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public void handle(OrderPaidEvent event) {
            recorder.record("contract", event);
        }
    }

    /** 注解声明在基类方法上。 */
    public static class BaseListenerBean {

        protected final EventRecorder recorder;

        public BaseListenerBean(EventRecorder recorder) {
            this.recorder = recorder;
        }

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION)
        public void on(OrderPaidEvent event) {
            recorder.record("base", event);
        }
    }

    /** 继承基类、自身未标注：注解方法应注册一次。 */
    public static class InheritingListenerBean extends BaseListenerBean {

        public InheritingListenerBean(EventRecorder recorder) {
            super(recorder);
        }
    }

    /** 覆写基类方法且不重复标注：应调用到最具体实现（子类覆写）。 */
    public static class OverridingListenerBean extends BaseListenerBean {

        public OverridingListenerBean(EventRecorder recorder) {
            super(recorder);
        }

        @Override
        public void on(OrderPaidEvent event) {
            recorder.record("overridden", event);
        }
    }
}
