package org.deep361.myddd.mq.kafka.consume;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.deep361.myddd.kernel.port.message.InboundMessage;
import org.deep361.myddd.kernel.port.message.MessageListener;
import org.deep361.myddd.mq.MqProvider;
import org.deep361.myddd.mq.listener.InboundDispatcher;
import org.deep361.myddd.mq.listener.MessageListenerRegistrar;
import org.deep361.myddd.mq.payload.MessagePayloadConverter;
import org.deep361.myddd.mq.route.MessageConsumptionPlan;
import org.deep361.myddd.mq.route.MessageRouteTable;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.kafka.core.ConsumerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 消费侧的真壳：注册表、分发器、消费计划<b>全部是生产类型</b>，替身只到厂商容器为止。
 *
 * <p><b>为什么不替 {@code InboundDispatcher}／{@code MessageConsumptionPlan}</b>：两个类都是 {@code final}
 * （Task 34 定的），本仓的测试风格也不靠 mock final 类；更关键的是构造代价实测很小——
 * {@code MessageListenerRegistrar} 只吃一个 {@code ListableBeanFactory}，所以
 * {@code new GenericApplicationContext()} + {@code registerBean(...)} + 直接调
 * {@code registrar.afterSingletonsInstantiated()} 就得到一个已冻结的注册表，<b>不需要 refresh，也不需要 broker</b>。
 *
 * <p><b>{@code received} 是实例字段而非静态字段</b>：监听 bean 经构造子拿到它，每个 rig 一份。
 * 静态列表会让上一条用例留下的记录漂进下一条断言，而 LM4 要的恰恰是"分发器一次都没被调到"。
 * 类型取 {@code CopyOnWriteArrayList}，因为 LM5 从两个线程投递。
 *
 * <p><b>命名冲突一条，别去"修"</b>：{@code java.util.function.Consumer}（监听 bean 的 sink 类型）与
 * {@code org.apache.kafka.clients.consumer.Consumer}（{@code createConsumer} 的返回类型）同名，两者在本文件
 * 都必须出现。这里 import 厂商那个、把 {@code java.util.function.Consumer} <b>逐处写全限定名</b>——反过来写
 * 会让 {@code CountingConsumerFactory} 的签名变成 {@code java.util.function.Consumer<Object,Object>}，那压根不匹配接口。
 */
public final class ConsumptionRig {

    /** CM12 的材料：被监听但<b>不</b>进路由表的通道，计划据此在启动期就抛。 */
    public static final String UNROUTED_CHANNEL = "nobody-routes-me";

    /** 一个组三条通道：CM3 断通道不串、CM4 断组内升序。 */
    public static final class OneGroupThreeChannels {

        private final java.util.function.Consumer<InboundMessage> sink;

        OneGroupThreeChannels(java.util.function.Consumer<InboundMessage> sink) {
            this.sink = sink;
        }

        @MessageListener(channel = "a-channel", group = "g-one")
        public void onA(InboundMessage message) {
            sink.accept(message);
        }

        @MessageListener(channel = "b-channel", group = "g-one")
        public void onB(InboundMessage message) {
            sink.accept(message);
        }

        @MessageListener(channel = "c-channel", group = "g-one")
        public void onC(InboundMessage message) {
            sink.accept(message);
        }
    }

    /** 三条通道三种组形态：两个显式组 + 一个未写 group（CM5/CM6）。 */
    public static final class ThreeGroupShapes {

        private final java.util.function.Consumer<InboundMessage> sink;

        ThreeGroupShapes(java.util.function.Consumer<InboundMessage> sink) {
            this.sink = sink;
        }

        @MessageListener(channel = "ch-zeta", group = "zeta")
        public void onZeta(InboundMessage message) {
            sink.accept(message);
        }

        @MessageListener(channel = "ch-alpha", group = "alpha")
        public void onAlpha(InboundMessage message) {
            sink.accept(message);
        }

        @MessageListener(channel = "ch-default")
        public void onDefault(InboundMessage message) {
            sink.accept(message);
        }
    }

    /** 两个失败形态：业务异常与 {@code Error}（LM2/LM3）。先记后抛，好让"部分成功"可见。 */
    public static final class Failing {

        private final java.util.function.Consumer<InboundMessage> sink;

        Failing(java.util.function.Consumer<InboundMessage> sink) {
            this.sink = sink;
        }

        @MessageListener(channel = "boom", group = "g-fail")
        public void onBoom(InboundMessage message) {
            sink.accept(message);
            throw new IllegalStateException("业务失败");
        }

        @MessageListener(channel = "err", group = "g-fail")
        public void onErr(InboundMessage message) {
            sink.accept(message);
            throw new StackOverflowError();
        }
    }

    /** 只有 {@code kafkaWithUnroutedListener()} 的 rig 才注册它：被监听但不在路由表里 ⇒ 计划在启动期就抛。 */
    public static final class Unrouted {

        @MessageListener(channel = UNROUTED_CHANNEL, group = "g-x")
        public void on(String payload) {
            throw new UnsupportedOperationException("本方法永不被调用：计划在订阅之前就失败");
        }
    }

    private final GenericApplicationContext context = new GenericApplicationContext();
    public final List<InboundMessage> received = new CopyOnWriteArrayList<>();
    public final CountingConsumerFactory consumerFactory;
    public final MessageListenerRegistrar registrar;
    public final InboundDispatcher dispatcher;
    public final MessageConsumptionPlan plan;

    /**
     * @param provider                把被监听通道路由到的 provider
     * @param groupIdInFactory        消费者工厂配置里的 {@code group.id}；{@code null} 表示"没配"
     * @param withDefaultGroupChannel 是否注册含<b>未写 group</b> 通道的监听类（CM5/CM6 需要，
     *                                CM5 的另一半"全显式组 ⇒ 一次都不读"需要它是 {@code false}）
     * @param withUnroutedListener    是否额外注册一个<b>没有路由</b>的监听类（CM12）
     */
    private ConsumptionRig(MqProvider provider, String groupIdInFactory, boolean withDefaultGroupChannel,
                           boolean withUnroutedListener) {
        context.registerBean(OneGroupThreeChannels.class, () -> new OneGroupThreeChannels(received::add));
        if (withDefaultGroupChannel) {
            context.registerBean(ThreeGroupShapes.class, () -> new ThreeGroupShapes(received::add));
        }
        context.registerBean(Failing.class, () -> new Failing(received::add));
        if (withUnroutedListener) {
            context.registerBean(Unrouted.class, Unrouted::new);
        }
        // 注册表要的是 ListableBeanFactory；给它 beanFactory 而不是 context：
        // AbstractApplicationContext.getBeanNamesForType 有"必须已 refresh"的断言，而本夹具的整个前提是不 refresh。
        this.registrar = new MessageListenerRegistrar(context.getBeanFactory());
        this.registrar.afterSingletonsInstantiated();
        this.dispatcher = new InboundDispatcher(registrar, new MessagePayloadConverter());
        Map<String, MqProvider> routes = new TreeMap<>();
        // 除 UNROUTED_CHANNEL 外全部路由到 provider；故意不给它路由，CM12 的前提才成立
        // （若把每条被监听通道都塞进路由表，计划就永远不会"解析不出 provider"）。
        registrar.channels().forEach(channel -> {
            if (!UNROUTED_CHANNEL.equals(channel)) {
                routes.put(channel, provider);
            }
        });
        this.plan = new MessageConsumptionPlan(new MessageRouteTable(routes, null), registrar);
        this.consumerFactory = new CountingConsumerFactory(groupIdInFactory);
    }

    /** 全部通道归 Kafka、工厂配了缺省组——绝大多数 CM/LM 用例的起点。 */
    public static ConsumptionRig kafka(String groupIdInFactory) {
        return new ConsumptionRig(MqProvider.KAFKA, groupIdInFactory, true, false);
    }

    /** 每条通道都写了自己的组 ⇒ CM5 的另一半：缺省组那一次读取都不该发生。 */
    public static ConsumptionRig kafkaWithExplicitGroupsOnly(String groupIdInFactory) {
        return new ConsumptionRig(MqProvider.KAFKA, groupIdInFactory, false, false);
    }

    /** 全部通道归 RocketMQ ⇒ {@code plan.subscriptionsFor(KAFKA)} 为空（CM1）。 */
    public static ConsumptionRig rocketMqOnly() {
        return new ConsumptionRig(MqProvider.ROCKETMQ, "g-unused", true, false);
    }

    /** 有监听通道不在路由表里 ⇒ 计划抛（CM12）。 */
    public static ConsumptionRig kafkaWithUnroutedListener() {
        return new ConsumptionRig(MqProvider.KAFKA, "g-any", true, true);
    }

    public GenericApplicationContext context() {
        return context;
    }

    /**
     * 只读配置并计数的消费者工厂。
     *
     * <p>{@link #createConsumer} 抛 {@code UnsupportedOperationException} 是有意的越界保护：本模块的用例
     * 一条都不该真建消费者（离线，§0.3-7）。{@link #isAutoCommit()} 返回 {@code true} 对齐实测的 Boot 缺省
     * （{@code KafkaProperties} 的 consumer 属性里没有 {@code enable.auto.commit} 键，而容器建消费者时又强制
     * 把它写成 {@code false}）。
     */
    public static final class CountingConsumerFactory implements ConsumerFactory<Object, Object> {

        private final Map<String, Object> configurationProperties;
        private int configurationReads;

        /** {@code public}：AC 组在 {@code config} 包里要用它替掉 Boot 的消费者工厂。 */
        public CountingConsumerFactory(String groupId) {
            this.configurationProperties = groupId == null
                    ? Map.of() : Map.of(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        }

        @Override
        public Consumer<Object, Object> createConsumer(String groupId, String clientIdPrefix, String clientIdSuffix,
                                                       Properties properties) {
            throw new UnsupportedOperationException("本夹具不建真消费者：离线用例只到容器为止");
        }

        @Override
        public boolean isAutoCommit() {
            return true;
        }

        @Override
        public Map<String, Object> getConfigurationProperties() {
            configurationReads++;
            return configurationProperties;
        }

        public int configurationReads() {
            return configurationReads;
        }
    }

    /**
     * 厂商入站记录的构造缝：topic + 任意类型载荷 + 消息头，其余入参给实测缺省。
     *
     * <p><b>用十一参构造而不是五参</b>：五参给的是<b>空</b> {@code RecordHeaders}，无法带消息头，
     * WF13 就没法造双值同名头。<b>头值走 {@code new RecordHeader(name, bytes)}，且允许 {@code bytes == null}</b>：
     * 实测该构造子对 null 值合法，而 {@code Headers.add(String, byte[])} 对 null 的行为没有一手实测——不写它。
     *
     * <p><b>{@code payload} 参数是 {@code Object} 而不是 {@code String}</b>：WF12 要造 {@code Integer} 载荷钉
     * "换错 deserializer 的报错"，WF11 要造 {@code null}（墓碑）。三个数字 partition=0、offset=77L、
     * timestamp=1_700_000_000_000L 被 WF9/WF11/WF12 直接引用（{@code messageId} 逐字 {@code a-channel-0-77}）。
     *
     * @param topic   通道名
     * @param payload 载荷，可为 {@code null}（墓碑）或非 {@code String}（部署错）
     * @param headers 消息头，值可为 {@code null}
     */
    public static ConsumerRecord<Object, Object> wire(String topic, Object payload, Map<String, String> headers) {
        Headers recordHeaders = new RecordHeaders();
        headers.forEach((name, value) -> recordHeaders.add(new RecordHeader(name,
                value == null ? null : value.getBytes(StandardCharsets.UTF_8))));
        return new ConsumerRecord<>(topic, 0, 77L, 1_700_000_000_000L, TimestampType.CREATE_TIME,
                ConsumerRecord.NULL_SIZE,
                payload == null ? ConsumerRecord.NULL_SIZE
                        : payload.toString().getBytes(StandardCharsets.UTF_8).length,
                null, payload, recordHeaders, Optional.empty());
    }

    /** 最简形态（无消息头）。 */
    public static ConsumerRecord<Object, Object> wire(String topic, Object payload) {
        return wire(topic, payload, Map.of());
    }
}
