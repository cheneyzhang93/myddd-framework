package org.deep361.myddd.mq.kafka.produce;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.metrics.KafkaMetric;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 厂商 {@code Producer} 的录制替身：本模块 51 条用例里没有一条需要 broker。
 *
 * <p>它记录的是<b>厂商 {@code ProducerRecord} 本身</b>（topic/key/value 字节/headers/partition），
 * 这正是本模块的职责所在，断言因此落在真正会上飞机的对象上。
 *
 * <p>三种失败形态各自可造（一手实测的形状，见 {@code k4out.txt}）：
 * {@link Failure.Immediate} —— future 立即异常完成，模板会在 {@code send()} 处<b>同步</b>抛
 * {@code KafkaException}（取证：K3 探针就死在这一形状上，{@code k3err.txt} 的
 * {@code KafkaException: Send failed} 栈停在 {@code KafkaTemplate.doSend}）；
 * {@link Failure.Late} —— future 由另一个线程延迟完成，调用方看到的是 {@code get()} 抛
 * {@code ExecutionException}；{@link Failure.ThrowOnSend} —— {@code send()} 当场抛运行时异常
 * （模拟"模板已销毁"这类没进 future 的错）。
 */
public final class RecordingKafkaProducer implements Producer<String, String> {

    /** 失败注入的三种形状。 */
    public sealed interface Failure {
        /** 无注入。 */
        record None() implements Failure {
        }

        /** future 立即异常完成（实测形状①：模板同步抛 KafkaException）。 */
        record Immediate(RuntimeException failure) implements Failure {
        }

        /** future 延迟 millis 后异常完成（实测形状②：调用方在 get() 处收到 ExecutionException）。 */
        record Late(long millis, RuntimeException failure) implements Failure {
        }

        /** send() 当场抛出（不进 future 的错）。 */
        record ThrowOnSend(RuntimeException failure) implements Failure {
        }
    }

    /** 一次厂商调用的观察。 */
    public record Sent(ProducerRecord<String, String> record, boolean callbackInvoked) {
    }

    private final List<Sent> sent = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();
    private Failure failure = new Failure.None();

    public List<Sent> sent() {
        return sent;
    }

    /** 全部厂商方法调用序（含未预期调用，用来钉"框架不自己 flush/close"）。 */
    public List<String> calls() {
        return calls;
    }

    public ProducerRecord<String, String> lastRecord() {
        return sent.get(sent.size() - 1).record();
    }

    public void failWith(RuntimeException vendorFailure) {
        this.failure = new Failure.Immediate(vendorFailure);
    }

    public void failWithLate(RuntimeException vendorFailure, long millis) {
        this.failure = new Failure.Late(millis, vendorFailure);
    }

    public void throwOnSend(RuntimeException failure) {
        this.failure = new Failure.ThrowOnSend(failure);
    }

    /** 恢复成功路径（夹具会被多条用例复用同一个实例时使用）。 */
    public void succeedAgain() {
        this.failure = new Failure.None();
    }

    @Override
    public CompletableFuture<RecordMetadata> send(ProducerRecord<String, String> record) {
        return send(record, null);
    }

    @Override
    public CompletableFuture<RecordMetadata> send(ProducerRecord<String, String> record, Callback callback) {
        calls.add("send");
        Failure current = failure;
        failure = new Failure.None();
        if (current instanceof Failure.ThrowOnSend(RuntimeException boom)) {
            sent.add(new Sent(record, false));
            throw boom;
        }
        RecordMetadata metadata = new RecordMetadata(new TopicPartition(record.topic(), 3), 77L, 3,
                System.currentTimeMillis(), sizeOf(record.key()), sizeOf(record.value()));
        if (current instanceof Failure.Immediate(RuntimeException boom)) {
            if (callback != null) {
                callback.onCompletion(null, boom);
                sent.add(new Sent(record, true));
            } else {
                sent.add(new Sent(record, false));
            }
            CompletableFuture<RecordMetadata> pending = new CompletableFuture<>();
            pending.completeExceptionally(boom);
            return pending;
        }
        if (current instanceof Failure.Late(long millis, RuntimeException boom)) {
            CompletableFuture<RecordMetadata> pending = new CompletableFuture<>();
            sent.add(new Sent(record, callback != null));
            new Thread(() -> {
                sleep(millis);
                pending.completeExceptionally(boom);
                if (callback != null) {
                    callback.onCompletion(null, boom);
                }
            }, "kafka-late-failure").start();
            return pending;
        }
        if (callback != null) {
            callback.onCompletion(metadata, null);
        }
        sent.add(new Sent(record, callback != null));
        return CompletableFuture.completedFuture(metadata);
    }

    @Override
    public void flush() {
        calls.add("flush");
    }

    @Override
    public List<PartitionInfo> partitionsFor(String topic) {
        calls.add("partitionsFor");
        return List.of();
    }

    @Override
    public Map<MetricName, ? extends Metric> metrics() {
        return Map.of();
    }

    @Override
    public void close() {
        calls.add("close()");
    }

    @Override
    public void close(Duration timeout) {
        calls.add("close(" + timeout + ")");
    }

    @Override
    public Uuid clientInstanceId(Duration timeout) {
        calls.add("clientInstanceId");
        return Uuid.randomUuid();
    }

    @Override
    public void initTransactions() {
        calls.add("initTransactions");
    }

    @Override
    public void beginTransaction() {
        calls.add("beginTransaction");
    }

    @Override
    public void commitTransaction() {
        calls.add("commitTransaction");
    }

    @Override
    public void abortTransaction() {
        calls.add("abortTransaction");
    }

    @Override
    public void sendOffsetsToTransaction(Map<TopicPartition, OffsetAndMetadata> offsets,
                                         org.apache.kafka.clients.consumer.ConsumerGroupMetadata groupMetadata) {
        calls.add("sendOffsetsToTransaction");
    }

    @Override
    public void registerMetricForSubscription(KafkaMetric metric) {
        calls.add("registerMetricForSubscription");
    }

    @Override
    public void unregisterMetricFromSubscription(KafkaMetric metric) {
        calls.add("unregisterMetricFromSubscription");
    }

    private static int sizeOf(String value) {
        return value == null ? -1 : value.length();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
