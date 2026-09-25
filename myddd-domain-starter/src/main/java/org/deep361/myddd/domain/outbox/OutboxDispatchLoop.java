package org.deep361.myddd.domain.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Outbox 投递循环：随容器启停的后台单线程轮询，固定间隔调用 {@link OutboxDispatcher#dispatchOnce()}。
 *
 * <p><b>单轮异常不杀循环</b>：任何 {@link RuntimeException} 只记 ERROR 日志，下一轮照常进行——否则一次存储抖动
 * 就会让可靠投递静默停摆（记录仍留在库里，但没人再取件）。
 *
 * <p><b>生命周期</b>：实现 {@link SmartLifecycle} 且不覆写 {@code getPhase()}（默认 {@code Integer.MAX_VALUE}），
 * 容器关闭时按 phase 降序停止——本循环最先停，避免其它 bean 已拆解后仍去取件。首次取件发生在启动后一个
 * {@code pollInterval}：避免与启动期其它初始化抢资源，也让"启动即批量取件"不会在数据库尚未就绪时发生。
 *
 * <p><b>可替换</b>：应用声明自己的 bean 即整体替换（例如交给 XXL-Job 调度，见 Plan 4）。
 */
public final class OutboxDispatchLoop implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatchLoop.class);

    private final OutboxDispatcher dispatcher;
    private final Duration pollInterval;
    private final Duration shutdownTimeout;
    private final Object monitor = new Object();

    private ScheduledExecutorService executor;
    private volatile boolean running;

    public OutboxDispatchLoop(OutboxDispatcher dispatcher, Duration pollInterval, Duration shutdownTimeout) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "OutboxDispatcher 不能为 null");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval 不能为 null");
        if (pollInterval.toMillis() < 1) {
            throw new IllegalArgumentException("pollInterval 必须 ≥ 1ms，实际 " + pollInterval);
        }
        this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout 不能为 null");
        if (shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdownTimeout 不能为负数，实际 " + shutdownTimeout);
        }
    }

    @Override
    public void start() {
        synchronized (monitor) {
            if (running) {
                return;
            }
            executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "myddd-outbox-dispatch");
                thread.setDaemon(true);
                return thread;
            });
            long intervalMillis = pollInterval.toMillis();
            executor.scheduleWithFixedDelay(this::dispatchSafely, intervalMillis, intervalMillis,
                    TimeUnit.MILLISECONDS);
            running = true;
            log.info("Outbox 投递循环已启动：pollInterval={}，shutdownTimeout={}", pollInterval, shutdownTimeout);
        }
    }

    @Override
    public void stop() {
        ScheduledExecutorService current;
        synchronized (monitor) {
            if (!running) {
                return;
            }
            running = false;
            current = executor;
            executor = null;
        }
        current.shutdownNow();
        try {
            if (!current.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.error("Outbox 投递循环未在 {} 内停止；在飞轮次已中断，未投出的记录留在库中等下次认领", shutdownTimeout);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        log.info("Outbox 投递循环已停止");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void dispatchSafely() {
        try {
            OutboxDispatcher.DispatchResult result = dispatcher.dispatchOnce();
            if (result.claimed() > 0) {
                log.debug("Outbox 投递一轮：认领 {}，投出 {}，重试 {}，死信 {}", result.claimed(), result.dispatched(),
                        result.retried(), result.dead());
            }
        } catch (RuntimeException ex) {
            log.error("Outbox 投递轮次失败（循环继续）：{}", ex.toString(), ex);
        }
    }
}
