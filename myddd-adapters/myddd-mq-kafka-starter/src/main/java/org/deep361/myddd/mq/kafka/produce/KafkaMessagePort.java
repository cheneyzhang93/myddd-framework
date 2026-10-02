package org.deep361.myddd.mq.kafka.produce;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.message.OutboundMessage;
import org.deep361.myddd.mq.MqProvider;
import org.deep361.myddd.mq.ProviderMessagePort;
import org.deep361.myddd.mq.header.OutboundMessageEnricher;
import org.deep361.myddd.mq.kafka.KafkaWireFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Kafka 生产端口：把框架消息写成 {@code ProducerRecord}，交给 Boot 装配的模板，并<b>等到终态</b>才返回。
 *
 * <p><b>为什么必须等</b>：内核 {@link org.deep361.myddd.kernel.port.message.MessagePort} 的实现契约
 * 第一条是"发送失败必须抛出异常，不得静默丢弃"，而唯一的调用方是 Outbox 投递器——它靠这句异常决定
 * "这条记录状态不改，下一轮重投"。{@code KafkaOperations} 的每个 {@code send} 都返回
 * {@code CompletableFuture<SendResult>}（实测：18 个抽象方法里 10 个 send/sendDefault，返回值全是它），
 * 不等就等于把失败丢进一个没人听的 future。
 *
 * <p><b>{@code flush()} 不是错误通道</b>：实测真 producer 指向不可达地址时
 * {@code flush()} 2 ms 返回且<b>不抛</b>，同一个错误在 {@code future.get()} 上才现形
 * （{@code 261 ms} 抛 {@code ExecutionException}/{@code TimeoutException}，
 * 取证 {@code D:/tmp/p4probe/kprobe/k1out.txt}）。所以端口只认 future，一次都不调 flush；
 * 模板内部要不要 flush 属于 {@code spring.kafka} 的部署选择（实测 Boot 用单参构造 ⇒ {@code autoFlush=true}），
 * 与本类的可观察结果无关。
 *
 * <p><b>失败有三条形状不同的路，三条都要接住</b>（一手实测，取证 {@code k4out.txt}；机制在
 * {@code KafkaTemplate.doSend} 的字节码异常表里最清楚——它对 {@code producer.send()} 返回的那个 future
 * 只补一次"已完成的就 get()"，异常表里只有 {@code InterruptedException} 与 {@code ExecutionException} 两项，
 * <b>{@code producer.send()} 本身抛出的运行时异常不在任何 catch 范围内</b>）：
 * ① producer 在 {@code send()} 返回前就判定失败（future 当场已是异常完成）⇒ 模板<b>同步抛</b>
 * {@link KafkaException}（消息固定是 "Send failed"，cause 是<b>厂商异常本身</b>）；
 * ② 失败晚到（broker 超时、被拒）⇒ {@code send()} 正常返回未完成的 future，{@code get()} 抛
 * {@link ExecutionException}，其 cause 是 {@code KafkaProducerException}（"Failed to send"，
 * 实测它 {@code extends KafkaException}）；
 * ③ 不经过 future 的那条：{@code producer.send()} 自己抛运行时异常（序列化器类型不对、模板已销毁）
 * ——它原样回到本方法，于是走"契约之外的运行时异常"那句翻译。
 * 只写 ① 会让所有 broker 侧失败变成"没抛异常但也没投递"，只写 ②③ 会让本地配置错误穿透成未被翻译的栈。
 *
 * <p><b>不碰的东西</b>：producer 生命周期（实测模板在每次 send 里 {@code createProducer()…close(PT5S)}，
 * 缓存策略是 {@code ProducerFactory} 的事）、分区选择（{@code ProducerRecord} 的 {@code partition} 恒为
 * {@code null}，厂商分区器说了算）、事务与 {@code sendOffsetsToTransaction}、重试主题。
 *
 * <p><b>阻塞时长不在框架手里，也不该在</b>：{@code get()} 没有超时参数，上限来自
 * {@code spring.kafka.producer.properties.max.block.ms} 与 {@code delivery.timeout.ms}（实测把这两条压到
 * 250/500 ms 时，一次失败在 261 ms 就回来了）。框架再加一层自己的超时会造出第二个真相：
 * "框架已经放弃"与"厂商还在重试"同时成立，而 Outbox 会据此重投出一条重复消息。
 *
 * <p>线程安全：本类无可变状态；{@code KafkaOperations} 本身线程安全，会被投递器线程池并发调用。
 */
public final class KafkaMessagePort implements ProviderMessagePort {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessagePort.class);

    private final KafkaOperations<String, String> template;
    private final OutboundMessageEnricher enricher;

    /**
     * @param template Boot 装配的模板（泛型在装配处受控转换，见 {@code MydddKafkaAutoConfiguration}）
     * @param enricher 壳的出站头单点，traceId 之类的框架头由它写入
     */
    public KafkaMessagePort(KafkaOperations<String, String> template, OutboundMessageEnricher enricher) {
        this.template = Objects.requireNonNull(template, "template 不能为 null");
        this.enricher = Objects.requireNonNull(enricher, "enricher 不能为 null");
    }

    @Override
    public MqProvider provider() {
        return MqProvider.KAFKA;
    }

    /**
     * {@code false}——Kafka 没有"每条消息指定投递时刻"的 API（实测：延迟只能靠消费者侧退避或外部调度）。
     *
     * <p>本方法与 {@link #send(OutboundMessage)} 里的拒发分支是 R6 的一对：能力声明与行为必须同源，
     * {@code MessagePortContractTestBase#supportsDelayedDelivery()} 读的就是这里（{@code MQ-C8} 钉）。
     */
    @Override
    public boolean supportsDelayedDelivery() {
        return false;
    }

    @Override
    public void send(OutboundMessage message) {
        Objects.requireNonNull(message, "message 不能为 null");
        OutboundMessage enriched = enricher.enrich(message);
        if (enriched.deliverAt() != null) {
            // 位置在映射之前：厂商一次都没被碰到，transportMessageCount() 恒为 0（DD2 钉这条）。
            throw new BizException(CommonErrorCode.ARGUMENT_INVALID,
                    "Kafka 不支持定点投递，已拒发：channel=" + enriched.channel()
                            + "，deliverAt=" + enriched.deliverAt()
                            + "（期望形态：deliverAt 为 null 即立即投递。需要定点投递请把该通道路由到 rocketmq——"
                            + "myddd.mq.channels.<通道名>.provider=rocketmq，那里 deliverAt 落到厂商的"
                            + " TIMER_DELIVER_MS（Task 35 的 MAP9）。本框架不做的一件事是把"
                            + "「10 分钟后再提醒」静默降级成「立刻提醒」）");
        }
        ProducerRecord<String, String> record = KafkaWireFormat.toProducerRecord(enriched);
        awaitTerminalState(record);
    }

    /**
     * 发出并等到终态。四条出口，每条的消息都含 {@code topic}/{@code key}/头数量，cause 原样保留。
     *
     * <p>载荷不进诊断消息：一条领域事件载荷可能是几 KB 的 JSON，进日志等于把业务数据复制到运维面；
     * 定位一条消息用 topic + key 足够（msgId 由 broker 分配，框架拿不到更早的标识）。
     */
    private void awaitTerminalState(ProducerRecord<String, String> record) {
        CompletableFuture<SendResult<String, String>> future;
        try {
            future = template.send(record);
        } catch (KafkaException failure) {
            // 诊断点名的是根因而不是 spring-kafka 的那句 "Send failed"（实测 cause 是厂商异常本身，
            // k3err.txt/k4out.txt A1）：用户要知道的是"超时还是被拒"，转述语给不出这个信息。
            throw new FrameworkException("Kafka 发送失败：" + describe(record) + "，厂商原因："
                    + reason(rootCause(failure)) + "。这条失败在 send() 返回前就已判定，消息没有进入厂商的发送队列，"
                    + "Outbox 可以安全重投", failure);
        } catch (RuntimeException failure) {
            throw new FrameworkException("Kafka 模板抛出了契约之外的运行时异常 "
                    + failure.getClass().getName() + "：" + describe(record) + "，厂商原因："
                    + reason(failure) + "。它不是 broker 的拒绝，通常是配置或调用方式的问题，"
                    + "请先核对 spring.kafka.* 与模板是否已被销毁", failure);
        }
        try {
            SendResult<String, String> result = future.get();
            if (log.isDebugEnabled()) {
                var metadata = result.getRecordMetadata();
                log.debug("Kafka 投递完成：topic={}，partition={}，offset={}，key={}", metadata.topic(),
                        metadata.partition(), metadata.offset(), record.key());
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new FrameworkException("Kafka 发送等待被中断：" + describe(record)
                    + "。中断标志已恢复，调用方（Outbox 投递器）据此结束本轮", failure);
        } catch (ExecutionException failure) {
            Throwable vendor = rootCause(failure);
            throw new FrameworkException("Kafka 未确认写入：" + describe(record) + "，厂商原因："
                    + reason(vendor) + "。future 里带回来的异常意味着 broker 没有给出 ack，"
                    + "本框架一律按发送失败处理，由 Outbox 重投", failure);
        }
    }

    private static String describe(ProducerRecord<String, String> record) {
        return "topic=" + record.topic() + "，key=" + record.key()
                + "，headers=" + record.headers().toArray().length;
    }

    /**
     * 剥到最里层：{@code ExecutionException → KafkaProducerException → 厂商异常}（实测两跳，
     * {@code k4out.txt} 的 B1/B2 两行）。停在中间那一层的话，用户看到的是 "Failed to send" 这句
     * spring-kafka 的转述，而不是"到底是超时、被拒还是序列化失败"。
     */
    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /** 异常类名 + 消息第一行：厂商消息的第二行（如果有）通常是文档链接，不是结论（Task 36 同一条手法）。 */
    private static String reason(Throwable failure) {
        String message = failure.getMessage();
        if (message == null) {
            return failure.getClass().getName();
        }
        int lineEnd = message.indexOf('\n');
        return failure.getClass().getSimpleName() + ": "
                + (lineEnd < 0 ? message : message.substring(0, lineEnd));
    }
}
