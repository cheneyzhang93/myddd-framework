package org.deep361.myddd.domain.outbox;

import org.deep361.myddd.kernel.port.message.MessagePort;
import org.deep361.myddd.kernel.port.message.OutboundMessage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 录制型消息端口（测试替身）：记录发出的消息，可注入"接下来 N 次发送失败"。 */
public final class RecordingMessagePort implements MessagePort {

    private final List<OutboundMessage> sent = new ArrayList<>();
    private final AtomicInteger remainingFailures = new AtomicInteger();
    private volatile String failureMessage = "message port 炸了";

    /** 让接下来的 {@code times} 次发送失败（用 {@link Integer#MAX_VALUE} 表示"一直失败"）。 */
    public void failNext(int times) {
        if (times < 0) {
            throw new IllegalArgumentException("times 不能为负数，实际 " + times);
        }
        remainingFailures.set(times);
    }

    /** 自定义失败原因（用于断言 lastError 的截断与内容）。 */
    public void failureMessage(String message) {
        this.failureMessage = Objects.requireNonNull(message, "message 不能为 null");
    }

    @Override
    public synchronized void send(OutboundMessage message) {
        Objects.requireNonNull(message, "message 不能为 null");
        if (remainingFailures.get() > 0) {
            remainingFailures.decrementAndGet();
            throw new IllegalStateException(failureMessage);
        }
        sent.add(message);
        notifyAll();
    }

    /** 已发送消息快照（发送顺序）。 */
    public synchronized List<OutboundMessage> sent() {
        return List.copyOf(sent);
    }

    /** 等待已发送条数达到期望值（投递循环断言用；不用轮询）。 */
    public synchronized boolean awaitSentCount(int expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (sent.size() < expected) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        return true;
    }
}
