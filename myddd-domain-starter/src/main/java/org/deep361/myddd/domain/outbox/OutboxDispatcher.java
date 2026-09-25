package org.deep361.myddd.domain.outbox;

import org.deep361.myddd.kernel.port.message.MessageHeaderNames;
import org.deep361.myddd.kernel.port.message.MessagePort;
import org.deep361.myddd.kernel.port.message.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Outbox 投递器：认领一批记录 → 逐条发送 → 按结果标记（投出 / 退避重试 / 死信）。
 *
 * <p><b>一轮 = 认领 + 逐条发送</b>：{@link #dispatchOnce()} 向 {@link OutboxStore#claimDispatchable} 要一批记录
 * （租约续到 {@code now + leaseDuration}），逐条发送；<b>单条发送失败不影响同批其他记录</b>——失败记录按退避策略
 * 重新排队或标记死信，其余照常投出。
 *
 * <p><b>至少一次（at-least-once）</b>：消息发出后 {@code update} 失败或进程崩溃时，租约过期后这条记录会被重新
 * 认领并重发——所以消费端必须按消息头 {@code myddd-event-id} 幂等。框架不追求 exactly-once；因此
 * "发送成功但标记失败"的异常必须<b>原样上抛</b>（吞掉会掩盖重复投递的事实），由投递循环记录日志并在下一轮继续。
 *
 * <p><b>消息契约</b>（消费端据此消费，跨进程稳定）：通道 = 记录通道；载荷 = 记录 payload（原样转发，不重新序列化）；
 * 顺序键 = {@code aggregateId}（同一聚合的事件同分区保序）；头 = {@code myddd-event-id} / {@code myddd-event-type} /
 * {@code myddd-trace-id}（记录无 traceId 时<b>不写</b>该头，避免消费端读到空串）；{@code deliverAt} 为 null。
 */
public final class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    /** {@code lastError} 的最大长度：异常消息可能极长，落库前必须截断（列宽有限）。 */
    private static final int MAX_ERROR_LENGTH = 500;

    private final MessagePort messagePort;
    private final OutboxStore store;
    private final OutboxBackoff backoff;
    private final OutboxDispatchOptions options;
    private final Clock clock;

    public OutboxDispatcher(MessagePort messagePort, OutboxStore store, OutboxBackoff backoff,
                            OutboxDispatchOptions options, Clock clock) {
        this.messagePort = Objects.requireNonNull(messagePort, "MessagePort 不能为 null");
        this.store = Objects.requireNonNull(store, "OutboxStore 不能为 null");
        this.backoff = Objects.requireNonNull(backoff, "OutboxBackoff 不能为 null");
        this.options = Objects.requireNonNull(options, "OutboxDispatchOptions 不能为 null");
        this.clock = Objects.requireNonNull(clock, "Clock 不能为 null");
    }

    /**
     * 投递一轮（同步执行；轮询由 {@link OutboxDispatchLoop} 负责）。
     *
     * @return 本轮统计（认领 / 投出 / 重试排队 / 死信）
     */
    public DispatchResult dispatchOnce() {
        Instant now = clock.instant();
        List<OutboxRecord> claimed = store.claimDispatchable(now, now.plus(options.leaseDuration()),
                options.batchSize());
        int dispatched = 0;
        int retried = 0;
        int dead = 0;
        for (OutboxRecord record : claimed) {
            try {
                messagePort.send(toMessage(record));
            } catch (RuntimeException ex) {
                OutboxRecord failed = recordFailure(record, ex);
                store.update(failed);
                if (failed.status() == OutboxStatus.DEAD) {
                    dead++;
                    log.error("Outbox 记录投递失败且重试耗尽，进入死信：eventId={}，通道={}",
                            record.eventId(), record.channel(), ex);
                } else {
                    retried++;
                    log.warn("Outbox 记录投递失败，将于 {} 重试（已失败 {} 次）：eventId={}，通道={}",
                            failed.nextRetryAt(), failed.retryCount(), record.eventId(), record.channel(), ex);
                }
                continue;
            }
            store.update(record.dispatched());
            dispatched++;
        }
        return new DispatchResult(claimed.size(), dispatched, retried, dead);
    }

    /** 失败记一次尝试：未耗尽则回到 PENDING 并给出下次可投递时间，耗尽则进死信。 */
    private OutboxRecord recordFailure(OutboxRecord record, RuntimeException ex) {
        int attempts = record.retryCount() + 1;
        String error = describe(ex);
        if (attempts < options.maxAttempts()) {
            return record.retrying(clock.instant().plus(backoff.nextDelay(attempts)), error);
        }
        return record.dead(error);
    }

    /** 失败描述：{@code 异常类名：message}，超过 {@value #MAX_ERROR_LENGTH} 字符截断。 */
    private static String describe(RuntimeException ex) {
        String message = ex.getMessage();
        String description = message == null || message.isBlank()
                ? ex.getClass().getName()
                : ex.getClass().getName() + "：" + message;
        return description.length() <= MAX_ERROR_LENGTH ? description : description.substring(0, MAX_ERROR_LENGTH);
    }

    private static OutboundMessage toMessage(OutboxRecord record) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(MessageHeaderNames.EVENT_ID, record.eventId());
        headers.put(MessageHeaderNames.EVENT_TYPE, record.eventType());
        if (record.traceId() != null) {
            headers.put(MessageHeaderNames.TRACE_ID, record.traceId());
        }
        return new OutboundMessage(record.channel(), record.payload(), record.aggregateId(), headers, null);
    }

    /** 一轮投递的结果统计。 */
    public record DispatchResult(int claimed, int dispatched, int retried, int dead) {
    }
}
