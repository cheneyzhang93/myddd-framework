package org.deep361.myddd.mq.kafka.produce;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.common.trace.TraceContext;
import org.deep361.myddd.kernel.port.message.MessageHeaderNames;
import org.deep361.myddd.kernel.port.message.OutboundMessage;
import org.deep361.myddd.mq.MqProvider;
import org.deep361.myddd.mq.ProviderMessagePort;
import org.deep361.myddd.mq.header.OutboundMessageEnricher;
import org.deep361.myddd.mq.kafka.KafkaWireFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 端口级用例：SPI 声明（PP4）+ 端口映射（KM8）+ 定点拒发（DD3）+ 阻塞语义（ST5）+ 失败翻译（FT9）= 29 条。
 *
 * <p>每条用例新建一套夹具（工厂/模板/端口是实例字段，JUnit 每个方法一个新实例）：实测模板
 * <b>每次 send 都 createProducer()</b>（{@code k3out.txt}），共享夹具时 {@code createCount()} 与
 * 跨替身求和读的就是上一条用例的账。
 *
 * <p>KM8 顺带解释"本模块为什么没有 Task 35 的 SEL 组"：内核 javadoc 承诺"同 key 同分区/同队列"，
 * Kafka 的 key <b>本身就是</b>厂商分区器（{@code DefaultPartitioner} 按 key 哈希）的输入，
 * 兑现这句承诺的正确实现恰好是"什么都不做"；RocketMQ 的 {@code KEYS} 只是查询索引、不参与队列选择，
 * 那边才必须自己写 selector。
 */
class KafkaMessagePortTest {

    private final RecordingKafkaProducerFactory factory = new RecordingKafkaProducerFactory();
    private final KafkaTemplate<String, String> template = RecordingKafkaProducerFactory.newTemplate(factory);
    private final KafkaMessagePort port = new KafkaMessagePort(template, new OutboundMessageEnricher());

    @AfterEach
    void clearTraceContext() {
        TraceContext.clear();
    }

    private static OutboundMessage msg(String channel, String payload, String key, Map<String, String> headers) {
        return new OutboundMessage(channel, payload, key, headers, null);
    }

    private int transportMessageCount() {
        return factory.all().stream().mapToInt(p -> p.sent().size()).sum();
    }

    // ---- PP：ProviderMessagePort 的 SPI 面 ----

    @Test
    void should_report_the_kafka_provider() {
        // 壳靠 provider() 收集端口（Task 33 的 AC3 才有对象）。
        assertThat(port.provider()).isEqualTo(MqProvider.KAFKA);
    }

    @Test
    void should_declare_no_delayed_delivery_support() {
        // R6 的 Kafka 侧答案：声明 false + DD 组拒发，能力声明与行为同源。
        // RocketMQ 侧 PP2 断的是 true，两条一起构成"能力声明"这个接口的全部意义。
        assertThat(port).isInstanceOf(ProviderMessagePort.class);
        assertThat(port.supportsDelayedDelivery()).isFalse();
    }

    @Test
    void should_enrich_the_message_before_mapping_it() {
        // enrich（Task 34）与映射（本模块）互不吞：trace 头与自定义头必须同时在场。
        TraceContext.set("pp3-trace");
        port.send(msg("order-events", "{}", null, Map.of("x-a", "1")));
        Map<String, String> wire = KafkaWireFormat.userHeaders(factory.last().lastRecord().headers());
        assertThat(wire)
                .containsEntry(MessageHeaderNames.TRACE_ID, "pp3-trace")
                .containsEntry("x-a", "1");
    }

    @Test
    void should_reject_null_arguments() {
        // 三个缝名与端口 javadoc/构造器逐字一致。
        assertThatThrownBy(() -> port.send(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("message 不能为 null");
        assertThatThrownBy(() -> new KafkaMessagePort(null, new OutboundMessageEnricher()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("template 不能为 null");
        assertThatThrownBy(() -> new KafkaMessagePort(template, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("enricher 不能为 null");
    }

    // ---- KM：端口级映射（"端口不加不删不改"的账）----

    @Test
    void should_hand_the_wire_record_to_the_template_untouched() {
        OutboundMessage message = msg("order-events", "{\"id\":7}", "k-1",
                new LinkedHashMap<>(Map.of("x-a", "1")));
        port.send(message);
        ProducerRecord<String, String> viaPort = factory.last().lastRecord();
        ProducerRecord<String, String> direct =
                KafkaWireFormat.toProducerRecord(new OutboundMessageEnricher().enrich(message));
        assertThat(viaPort.topic()).isEqualTo(direct.topic());
        assertThat(viaPort.key()).isEqualTo(direct.key());
        assertThat(viaPort.value()).isEqualTo(direct.value());
        // 头数相同 + 逐个同名同值，由数组等值一次钉住（RecordHeader.equals 比的就是 key 与字节数组）。
        assertThat(viaPort.headers().toArray()).isEqualTo(direct.headers().toArray());
        assertThat(KafkaWireFormat.userHeaders(viaPort.headers()))
                .isEqualTo(KafkaWireFormat.userHeaders(direct.headers()));
    }

    @Test
    void should_keep_the_payload_a_string_on_the_whole_path() {
        // 框架不做 JSON 包装、不做 base64、不做第二次编码：Boot 缺省 StringSerializer，
        // 载荷从 OutboundMessage 到 record.value() 全程是同一个 String（实测 k5out.txt 的 buildProperties()）。
        port.send(OutboundMessage.of("order-events", "订单已支付 ¥100 ✅"));
        Object value = factory.last().lastRecord().value();
        assertThat(value).isInstanceOf(String.class).isEqualTo("订单已支付 ¥100 ✅");
        assertThat(StringSerializer.class.getName())
                .isEqualTo("org.apache.kafka.common.serialization.StringSerializer");
    }

    @Test
    void should_write_the_three_framework_header_names_verbatim() {
        // 不需要 RocketMQ MAP8 的"确认框架头名不在厂商保留名单"半条：Kafka 没有这个名单（KM7 是替代证据）。
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(MessageHeaderNames.TRACE_ID, "t1");
        headers.put(MessageHeaderNames.EVENT_ID, "e1");
        headers.put(MessageHeaderNames.EVENT_TYPE, "com.example.OrderPaid");
        port.send(msg("order-events", "{}", null, headers));
        Map<String, String> wire = KafkaWireFormat.userHeaders(factory.last().lastRecord().headers());
        assertThat(wire)
                .containsEntry("myddd-trace-id", "t1")
                .containsEntry("myddd-event-id", "e1")
                .containsEntry("myddd-event-type", "com.example.OrderPaid");
    }

    @Test
    void should_leave_the_blank_channel_rejection_to_the_kernel() {
        // 名字合法性只有一份规则（内核 record）：这里抛的是 IllegalArgumentException，
        // 不是端口的 FrameworkException，且工厂一次都没被碰。
        assertThatThrownBy(() -> port.send(msg("  ", "{}", null, Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(factory.createCount()).isZero();
    }

    @Test
    void should_map_a_dotted_channel_onto_the_topic_without_complaining() {
        // 与 Task 36 DC3 组成一对：同一个名字 Kafka 合法、RocketMQ 订阅期被拒。
        // 框架不"取交集"抹平（那等于为 Kafka 自造一份 RocketMQ 的名单，§0.3-5）。
        port.send(OutboundMessage.of("order.events", "{}"));
        assertThat(factory.last().lastRecord().topic()).isEqualTo("order.events");
    }

    @Test
    void should_build_a_new_producer_per_send_and_never_touch_the_partition() {
        // 实测 k3out.txt：[createProducer, send] × 每次 send；框架不选分区（partition 恒 null，
        // 四参 ProducerRecord 构造框架一律不用）。
        port.send(OutboundMessage.of("order-events", "1"));
        port.send(OutboundMessage.of("order-events", "2"));
        assertThat(factory.createCount()).isEqualTo(2);
        assertThat(factory.all()).allSatisfy(p -> assertThat(p.sent()).hasSize(1));
        assertThat(factory.all()).allSatisfy(p -> assertThat(p.lastRecord().partition()).isNull());
    }

    @Test
    void should_have_no_reserved_header_name_list_at_all() {
        // 钉"框架没有自造第二套名单"：RocketMQ 侧被拒的六个名字在这里全部原样上线。
        // 不是"Kafka 更宽松所以更好"的宣言——两侧都只服从各自厂商的真实规则。
        for (String name : List.of("KEYS", "TAGS", "UNIQ_KEY", "WAIT", "DELAY", "TRACE_ON")) {
            RecordingKafkaProducerFactory fresh = new RecordingKafkaProducerFactory();
            KafkaMessagePort freshPort = new KafkaMessagePort(
                    RecordingKafkaProducerFactory.newTemplate(fresh), new OutboundMessageEnricher());
            freshPort.send(msg("order-events", "{}", null, Map.of(name, "v")));
            ProducerRecord<String, String> record = fresh.last().lastRecord();
            assertThat(KafkaWireFormat.valueOf(record.headers().lastHeader(name))).isEqualTo("v");
            assertThat(KafkaWireFormat.userHeaders(record.headers())).containsEntry(name, "v");
        }
    }

    @Test
    void should_delegate_partitioning_to_the_vendor_for_a_stable_key() {
        // 本条的存在理由见类 javadoc 的 KM8 段：兑现"同 key 同分区"的正确实现是"什么都不做"。
        for (int i = 0; i < 3; i++) {
            port.send(OutboundMessage.of("order-events", "{}", "order-42"));
        }
        List<ProducerRecord<String, String>> records =
                factory.all().stream().map(RecordingKafkaProducer::lastRecord).toList();
        assertThat(records).hasSize(3);
        assertThat(records).allSatisfy(record -> {
            assertThat(record.key()).isEqualTo("order-42");
            assertThat(record.partition()).isNull();
        });
    }

    // ---- DD：R6 的"要么真支持、要么明确拒发" ----

    @Test
    void should_refuse_a_message_with_a_delivery_time() {
        // Kafka 无定点投递 API：这是调用方用法错误 ⇒ BizException/ARGUMENT_INVALID，不是框架故障。
        // 断"含"不断相等：ARGUMENT_INVALID 的兜底文案会把长文案包在中间（实测 CommonErrorCode.java:16），
        // 整串相等等价于把内核渲染规则钉死在 provider 测试里。
        Instant deliverAt = Instant.now().plus(Duration.ofMinutes(10));
        BizException failure = assertThrows(BizException.class, () -> port.send(
                new OutboundMessage("order-events", "{}", null, Map.of(), deliverAt)));
        assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.ARGUMENT_INVALID);
        assertThat(failure.getMessage())
                .contains("已拒发")
                .contains("channel=order-events")
                .contains("deliverAt=")
                .contains("myddd.mq.channels.<通道名>.provider=rocketmq")
                .contains("TIMER_DELIVER_MS")
                .contains("静默降级");
    }

    @Test
    void should_refuse_before_touching_the_transport() {
        // 钉端口里"拒发分支在映射之前"的位置：挪到 toProducerRecord 之后厂商就白挨一次记录构造。
        // （与 KM1 一起把顺序钉死。）
        Instant deliverAt = Instant.now().plus(Duration.ofMinutes(10));
        assertThrows(BizException.class, () -> port.send(
                new OutboundMessage("order-events", "{}", null, Map.of(), deliverAt)));
        assertThat(factory.createCount()).isZero();
        assertThat(transportMessageCount()).isZero();
    }

    @Test
    void should_not_refuse_an_absent_delivery_time() {
        // DD1/DD2 的对照组：缺它则"任何消息一律拒发"也能让那两条全绿。
        assertThatCode(() -> port.send(new OutboundMessage("order-events", "{}", null, Map.of(), null)))
                .doesNotThrowAnyException();
        assertThat(transportMessageCount()).isEqualTo(1);
    }

    // ---- ST：阻塞语义与线程/网络卫生 ----

    @Test
    void should_complete_without_throwing_on_the_success_path() {
        // 成功基线（§0.3-8）。注意这条不是"必须等待"的证据——等待的杀手是 ST3。
        // 调用序不在这里钉（ST1 钉的就是它），本条只钉"不抛 + 厂商确实收到一条"。
        port.send(OutboundMessage.of("order-events", "{}"));
        assertThat(factory.last().sent()).hasSize(1);
    }

    @Test
    void should_never_flush_and_leave_the_producer_lifecycle_to_the_template() {
        // 框架发起的厂商调用恰好只有一次 "send"。三条一手实测支撑这个形状：flush() 在不可达地址时 2 ms
        // 返回且不抛（k1out.txt，它不是错误通道）；template.flush() 会额外 [createProducer, flush,
        // close(PT5S)]（k3out.txt 第 4 段，白建 producer 还拿不到信息）；而模板对 producer 寿命的处理是
        // "每次 send 新建一个、发完自己 close(PT5S)"（本端口类注释"不碰的东西"段，夹具的 autoFlush=false
        // 只挡得住 flush，挡不住那次 close——实测 ["send","close(PT5S)"]）。
        // 所以 close 是 spring-kafka 发起的、不是框架发起的：端口连 producer 的引用都拿不到，想调也调不到。
        // 被排除的只有 close(...)，flush 与五个事务方法一个都不许出现。
        port.send(OutboundMessage.of("order-events", "{}"));
        assertThat(frameworkCalls(factory.last())).containsExactly("send");
    }

    /** 剔除模板自己的 producer 生命周期调用（{@code close(PT5S)}），只留框架有能力发起的那些。 */
    private static List<String> frameworkCalls(RecordingKafkaProducer producer) {
        return producer.calls().stream().filter(call -> !call.startsWith("close(")).toList();
    }

    @Test
    void should_block_until_a_late_vendor_outcome_arrives() {
        // "等到终态"的唯一正面证据：删掉 get() 或换成不等终态的写法，本条必红。
        factory.arm(p -> p.failWithLate(vendorTimeout(), 150));
        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> port.send(OutboundMessage.of("order-events", "{}")))
                .isInstanceOf(FrameworkException.class);
        assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(150);
    }

    @Test
    void should_support_concurrent_sends_without_shared_state() {
        // 内核 MessagePort 实现契约第二条"线程安全"的钉子：8 线程各发 1 条、同一个端口实例。
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> failures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                pool.submit(() -> {
                    try {
                        port.send(OutboundMessage.of("channel-" + index, "{}"));
                    } catch (Throwable failure) {
                        synchronized (failures) {
                            failures.add(failure);
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            pool.shutdownNow();
        }
        assertThat(failures).isEmpty();
        assertThat(transportMessageCount()).isEqualTo(threads);
        assertThat(factory.createCount()).isEqualTo(threads);
    }

    @Test
    void should_open_no_network_and_no_thread_by_itself() throws InterruptedException {
        // 装配本模块的端口 ≠ 建立网络连接：producer 在首次 send 才出现（k3out.txt）。
        // 防的是"bean 初始化期预热 producer"那一类改动——真 KafkaProducer 的 ctor 要 1084 ms
        // 且起一条 kafka-producer-network-thread（k1out.txt），那条线程出现在 surefire 里就说明
        // 有人把替身换成了真的。
        assertThat(factory.createCount()).isZero();
        assertThat(awaitNoKafkaThreads(5000)).isEmpty();
    }

    // ---- FT：两条失败路都翻译 ----

    @Test
    void should_translate_an_immediate_failure_raised_at_the_send_call() {
        // 实测形状（k4out.txt A1 + KafkaTemplate.doSend 的字节码异常表）：模板在 send() 内部
        // 对"已经完成的 future"补一次 get()，把 ExecutionException 的 cause 原样塞进
        // new KafkaException("Send failed", cause) 同步抛出——所以厂商异常是祖父，不是父。
        org.apache.kafka.common.errors.TimeoutException vendor = vendorTimeout();
        factory.arm(p -> p.failWith(vendor));
        FrameworkException failure = assertThrows(FrameworkException.class,
                () -> port.send(msg("order-events", "{}", "k-1", Map.of())));
        assertThat(failure)
                .hasMessageContaining("发送失败")
                .hasMessageContaining("topic=order-events")
                .hasMessageContaining("key=")
                .hasMessageContaining("headers=")
                .hasMessageContaining("TimeoutException: not present in metadata after 250 ms")
                .hasMessageContaining("send() 返回前")
                .hasMessageContaining("Outbox");
        assertThat(failure.getCause()).isInstanceOf(KafkaException.class).hasMessageContaining("Send failed");
        assertThat(failure.getCause().getCause()).isSameAs(vendor);
    }

    @Test
    void should_translate_a_late_failure_after_the_future_completes() {
        // 实测形状 k4out.txt B1/B2：send() 正常返回未完成 future，get() 抛 ExecutionException，
        // cause 是 KafkaProducerException: Failed to send。这条与上一条一起构成"两条路都接住"。
        factory.arm(p -> p.failWithLate(vendorTimeout(), 50));
        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> port.send(msg("order-events", "{}", "k-1", Map.of())))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("未确认写入")
                .hasMessageContaining("topic=order-events")
                .hasMessageContaining("key=")
                .hasMessageContaining("headers=")
                .hasMessageContaining("TimeoutException")
                .hasMessageContaining("not present in metadata");
        assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(50);
    }

    @Test
    void should_wrap_a_runtime_exception_that_is_not_a_kafka_failure() {
        // 原文异常名实测自 k1out.txt 末段（"已 close 再 send()"）。
        // ⚠️ 变异方向：catch 顺序写反时判红的是 FT1（KafkaException 是 RuntimeException 的后代，
        // 它会掉进本条的句式），本条的绿不能当"顺序没事"的证据。
        IllegalStateException boom =
                new IllegalStateException("Cannot perform operation after producer has been closed");
        factory.arm(p -> p.throwOnSend(boom));
        assertThatThrownBy(() -> port.send(msg("order-events", "{}", "k-1", Map.of())))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("java.lang.IllegalStateException")
                .hasMessageContaining("契约之外的运行时异常")
                .hasMessageContaining("spring.kafka")
                .hasMessageContaining("模板是否已被销毁")
                .hasMessageContaining("topic=order-events")
                .hasCauseReference(boom);
    }

    @Test
    void should_keep_the_original_exception_as_the_cause_not_the_stripped_root() {
        // 防"顺手把 cause 换成 root"：链子必须原样留给 getCause()，中间层 KafkaProducerException
        // 的类型信息是"模板确认过失败"的证据；诊断文本里出现的则应是根因类名。
        factory.arm(p -> p.failWithLate(vendorTimeout(), 20));
        assertThatThrownBy(() -> port.send(OutboundMessage.of("order-events", "{}")))
                .isInstanceOf(FrameworkException.class)
                .hasCauseInstanceOf(ExecutionException.class)
                .hasMessageContaining("TimeoutException");
    }

    @Test
    void should_walk_the_whole_cause_chain_into_the_diagnosis() {
        // 三层链实测形状（k4out.txt B2）：ExecutionException → KafkaProducerException → 厂商异常。
        // 消息必须点名最里层，且不出现 spring-kafka 的转述语 "Failed to send"——
        // 本条是 rootCause 剥层深度的唯一证据：某版本只剥一跳时它红，不会静默。
        factory.arm(p -> p.failWithLate(vendorTimeout(), 20));
        assertThatThrownBy(() -> port.send(OutboundMessage.of("order-events", "{}")))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("not present in metadata after 250 ms")
                .hasMessageNotContaining("Failed to send");
    }

    @Test
    void should_restore_the_interrupt_flag_and_not_guess_the_outcome() {
        // 框架不猜"消息到底上没上飞机"：中断时它可能已在途，只把中断交回上层（Task 35 FL6 同一条纪律）。
        factory.arm(p -> p.failWithLate(vendorTimeout(), 200));
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> port.send(msg("order-events", "{}", "k-1", Map.of())))
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("被中断")
                    .hasMessageContaining("中断标志已恢复")
                    .hasMessageContaining("topic=order-events")
                    .hasMessageContaining("key=")
                    .hasMessageContaining("headers=");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // 必须清标志：中断位泄漏会把同 JVM 里后面所有用例一起毒掉。
            Thread.interrupted();
        }
    }

    @Test
    void should_let_an_error_pass_through_untouched() {
        // 钉"不要把 catch (RuntimeException) 扩成 catch (Throwable)"（Task 36 LM3 的镜像）。
        // 本条也是唯一一条不需要录制替身注入的 FT 用例：直接在模板子类上炸。
        KafkaTemplate<String, String> exploding =
                new KafkaTemplate<>(factory, false) {
                    @Override
                    public CompletableFuture<SendResult<String, String>> send(
                            ProducerRecord<String, String> record) {
                        throw new AssertionError("业务线程栈错误");
                    }
                };
        KafkaMessagePort explodingPort = new KafkaMessagePort(exploding, new OutboundMessageEnricher());
        assertThatThrownBy(() -> explodingPort.send(OutboundMessage.of("order-events", "{}")))
                .isInstanceOf(AssertionError.class)
                .isNotInstanceOf(FrameworkException.class);
    }

    @Test
    void should_keep_the_payload_out_of_every_diagnosis() {
        // 一条领域事件可能几 KB 业务数据，进异常等于把它复制进运维面；定位一条消息用 topic+key 足够。
        String payload = "x".repeat(4096);
        factory.arm(p -> p.failWith(vendorTimeout()));
        Throwable failure = assertThrows(FrameworkException.class,
                () -> port.send(OutboundMessage.of("order-events", payload)));
        assertThat(failure.getMessage()).doesNotContain(payload.substring(0, 20));
        assertThat(failure.getMessage()).doesNotContain("payload");
        assertThat(failure.getMessage()).contains("headers=");
    }

    @Test
    void should_translate_a_class_cast_from_a_mismatched_serializer() {
        // 部署方把 value.serializer 换成 ByteArraySerializer 的后果（FT1/FT3 之外的形状）。
        // 这条是装配处那次 @SuppressWarnings("unchecked") 的兜底面：强转的安全性靠两条失败路 +
        // 这一句翻译，不靠运气。
        ClassCastException boom =
                new ClassCastException("class java.lang.String cannot be cast to class [B");
        factory.arm(p -> p.throwOnSend(boom));
        assertThatThrownBy(() -> port.send(msg("order-events", "{}", "k-1", Map.of())))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("java.lang.ClassCastException")
                .hasMessageContaining("class java.lang.String cannot be cast to class [B")
                .hasCauseReference(boom);
    }

    // ---- 辅助 ----

    private static org.apache.kafka.common.errors.TimeoutException vendorTimeout() {
        return new org.apache.kafka.common.errors.TimeoutException("not present in metadata after 250 ms");
    }

    /**
     * 等短暂线程（夹具的 kafka-late-failure 替身，完成 future 后自行退出）离场，再交出最后的 kafka 线程清单。
     *
     * <p>真 KafkaProducer 起的 kafka-producer-network-thread 是常驻线程，等待不会让它消失——
     * 超时后本方法把"仍然在场"的清单交给调用方断言，杀手不丢：装配期预热 producer 的改动在这里必红。
     */
    private static List<String> awaitNoKafkaThreads(long millisToWait) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millisToWait;
        List<String> seen = kafkaThreads();
        while (!seen.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
            seen = kafkaThreads();
        }
        return seen;
    }

    private static List<String> kafkaThreads() {
        List<String> out = new ArrayList<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            String name = thread.getName();
            if (name != null && name.toLowerCase().contains("kafka")) {
                out.add(name + "(daemon=" + thread.isDaemon() + ")");
            }
        }
        return out;
    }
}
