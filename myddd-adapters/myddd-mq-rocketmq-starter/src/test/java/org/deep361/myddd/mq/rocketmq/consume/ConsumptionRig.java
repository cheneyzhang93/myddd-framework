package org.deep361.myddd.mq.rocketmq.consume;

import org.deep361.myddd.kernel.port.message.InboundMessage;
import org.deep361.myddd.kernel.port.message.MessageListener;
import org.deep361.myddd.mq.MqProvider;
import org.deep361.myddd.mq.listener.InboundDispatcher;
import org.deep361.myddd.mq.listener.MessageListenerRegistrar;
import org.deep361.myddd.mq.payload.MessagePayloadConverter;
import org.deep361.myddd.mq.rocketmq.config.MydddRocketMqProperties;
import org.deep361.myddd.mq.route.MessageConsumptionPlan;
import org.deep361.myddd.mq.route.MessageRouteTable;
import org.springframework.context.support.GenericApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 消费侧的真壳：注册表、分发器、消费计划<b>全部是生产类型</b>，替身只到厂商消费者为止（Step 8 的夹具）。
 *
 * <p><b>为什么不替 {@code InboundDispatcher}／{@code MessageConsumptionPlan}</b>：两个类都是 {@code final}
 * （Task 34 定的），本仓的测试风格也不靠 mock final 类；更关键的是构造代价实测很小——
 * {@code MessageListenerRegistrar} 只吃一个 {@code ListableBeanFactory}，所以
 * {@code new GenericApplicationContext()} + {@code registerBean(...)} + 直接调
 * {@code registrar.afterSingletonsInstantiated()} 就得到一个已冻结的注册表，
 * <b>不需要 refresh，也不需要 broker</b>（§0.3-7）。refresh 一次会真起 {@code SmartLifecycle}
 * （{@code isAutoStartup()} 实测 {@code true}），而 {@code NAME_SERVER} 是个假地址。
 *
 * <p><b>为什么监听类是静态嵌套的</b>：{@code @MessageListener} 的注解值必须是编译期常量才进得了路由表，
 * 所以通道名与组名只能写在源码里。<b>每个通道名在本夹具内全局唯一</b>：注册表对"同一通道两个组"是快速失败
 * （Task 34 的 {@code LS13}），夹具里重名会让全部用例栽在同一个构造期异常上。
 *
 * <p><b>{@code received} 是实例字段而非静态字段</b>：监听 bean 经构造子拿到它，每个 rig 一份。
 * 静态列表会让上一条用例留下的记录漂进下一条断言，而 {@code LM4} 要的恰恰是"third 一次都没被调"、
 * {@code LM5} 要的是"计数 0"。类型取 {@code CopyOnWriteArrayList}，因为有两条用例从两个线程投递。
 *
 * <p>组 → 通道清单（{@code CM3}/{@code CM4} 的断言值全部由此现推，别在用例里另记一份）：
 * {@code alpha}/{@code ch-alpha}；{@code g-fail}/{@code boom, err, first, third}；
 * {@code g-one}/{@code a-channel, b-channel, c-channel}；
 * {@code myddd-consumer}（配置缺省组）/{@code ch-default}（注解未写 group）；{@code zeta}/{@code ch-zeta}。
 * 组名升序因此是 {@code alpha → g-fail → g-one → myddd-consumer → zeta}：{@code CM7} 注入升序第一个组、
 * {@code CM8} 注入最后一个组，两条判据互不重叠。
 */
public final class ConsumptionRig {

    /** 假 name-server：本夹具一条用例都不联网（真投递是 Task 40 的 {@code *IT}）。 */
    public static final String NAME_SERVER = "127.0.0.1:9876";

    /**
     * 声明位置不许挪到下面的 {@code public} 字段之后：字段初始化器按声明顺序执行，而构造子体第一行就是
     * {@code context.registerBean(...)}。Java 允许构造子里引用"后面才声明"的字段而编译期不报错，
     * 值是 {@code null}——本夹具的 {@code context} 与 {@code received} 都必须先就位。
     */
    private final GenericApplicationContext context = new GenericApplicationContext();

    public final List<InboundMessage> received = new CopyOnWriteArrayList<>();
    /**
     * 跨消费者的关闭顺序（{@code CM9} 的唯一可观察物）。
     *
     * <p><b>为什么单独记这一张日志</b>：每个录制消费者自己的 {@code calls()} 只说"我被关过"，
     * 说不出"我和同批其余消费者谁先关"。{@code stopStarted()} 的逆序（最后启动的最先关）
     * 是一句顺序断言，跨实例的顺序在任何单个实例的日志里都不存在，只能由两侧共写一张表来显形。
     */
    public final List<String> closeOrder = new CopyOnWriteArrayList<>();
    public final MessageListenerRegistrar registrar;
    public final InboundDispatcher dispatcher;
    public final MessageConsumptionPlan plan;
    /** {@code public}：{@code CM6} 的第二轮要在 {@code start()} 之前改 {@code consumer.*} 两项。 */
    public final MydddRocketMqProperties properties;
    public final RecordingFactory factory;
    public final RocketMqConsumerManager manager;

    /** 一个组三条通道：{@code CM2} 断这个组的完整调用顺序，{@code CM3} 断通道不串。 */
    public static final class OneGroupThreeChannels {

        private final Consumer<InboundMessage> sink;

        OneGroupThreeChannels(Consumer<InboundMessage> sink) {
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

    /** 三条通道三种组形态：两个显式组 + 一个未写 group（{@code CM5}）。 */
    public static final class ThreeGroupShapes {

        private final Consumer<InboundMessage> sink;

        ThreeGroupShapes(Consumer<InboundMessage> sink) {
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

    /**
     * 一个组四条通道，覆盖 {@code LM2}–{@code LM7} 的全部失败形态。
     *
     * <p><b>{@code first} 与 {@code third} 必须和 {@code boom} 同组</b>：{@code LM4} 断的是"厂商把一个批次的
     * 多条消息一次递给<b>同一个消费者</b>，第一条失败就整批重投"。把它们挪进第二个组，用例就得跨两个消费者
     * 投"一批"——那压根不是厂商的投递形态。
     *
     * <p><b>先记后抛</b>：{@code LM2}/{@code LM4} 要断"失败之前那一条确实进过业务方法"。
     */
    public static final class Failing {

        private final Consumer<InboundMessage> sink;

        Failing(Consumer<InboundMessage> sink) {
            this.sink = sink;
        }

        @MessageListener(channel = "first", group = "g-fail")
        public void onFirst(InboundMessage message) {
            sink.accept(message);
        }

        @MessageListener(channel = "boom", group = "g-fail")
        public void onBoom(InboundMessage message) {
            sink.accept(message);
            throw new IllegalStateException("业务失败");
        }

        @MessageListener(channel = "third", group = "g-fail")
        public void onThird(InboundMessage message) {
            sink.accept(message);
        }

        @MessageListener(channel = "err", group = "g-fail")
        public void onErr(InboundMessage message) {
            sink.accept(message);
            throw new StackOverflowError();
        }
    }

    /** 只有 {@code CM12} 的 rig 才注册它：被监听但不在路由表里 ⇒ 计划在启动期就抛。 */
    public static final class Unrouted {

        /** 编译期常量：注解值必须是常量才进得了路由表，{@code CM12} 的断言与本夹具的排除读同一个名字。 */
        public static final String CHANNEL = "nobody-routes-me";

        @MessageListener(channel = CHANNEL, group = "g-x")
        public void on(String payload) {
            throw new UnsupportedOperationException("本方法永不被调用：计划在订阅之前就失败");
        }
    }

    /**
     * @param provider             把被监听通道路由到的 provider（{@code CM1} 传 KAFKA ⇒ 本模块零消费者）
     * @param withUnroutedListener 是否额外注册一个<b>没有路由</b>的监听类（{@code CM12}）
     */
    private ConsumptionRig(MqProvider provider, boolean withUnroutedListener) {
        context.registerBean(OneGroupThreeChannels.class, () -> new OneGroupThreeChannels(received::add));
        context.registerBean(ThreeGroupShapes.class, () -> new ThreeGroupShapes(received::add));
        context.registerBean(Failing.class, () -> new Failing(received::add));
        if (withUnroutedListener) {
            context.registerBean(Unrouted.class, Unrouted::new);
        }
        // 注册表要的是 ListableBeanFactory；直接给它 beanFactory 而不是 context——
        // AbstractApplicationContext.getBeanNamesForType 有"必须已 refresh"的断言，而本夹具的整个前提是"不 refresh"。
        this.registrar = new MessageListenerRegistrar(context.getBeanFactory());
        this.registrar.afterSingletonsInstantiated();
        this.dispatcher = new InboundDispatcher(registrar, new MessagePayloadConverter());
        Map<String, MqProvider> routes = new TreeMap<>();
        // CM12 的前提是"有条被监听的通道不在路由表里"，所以这张表必须显式少它那一行；
        // 用"给路由表传缺省 provider"来绕开，等于把这条用例的判据从根上取消。
        registrar.channels().stream()
                .filter(channel -> !(withUnroutedListener && Unrouted.CHANNEL.equals(channel)))
                .forEach(channel -> routes.put(channel, provider));
        this.plan = new MessageConsumptionPlan(new MessageRouteTable(routes, null), registrar);
        this.properties = new MydddRocketMqProperties();
        this.properties.setNameServer(NAME_SERVER);
        this.factory = new RecordingFactory(closeOrder);
        this.manager = new RocketMqConsumerManager(properties, plan, dispatcher, factory);
    }

    /** 五个组、十条通道：绝大多数 {@code CM}/{@code LM} 用例的起点。 */
    public static ConsumptionRig rocketMq() {
        return new ConsumptionRig(MqProvider.ROCKETMQ, false);
    }

    /** 十条通道全部路由到 Kafka ⇒ {@code plan.subscriptionsFor(ROCKETMQ)} 为空（{@code CM1}）。 */
    public static ConsumptionRig kafkaOnly() {
        return new ConsumptionRig(MqProvider.KAFKA, false);
    }

    /** 有条被监听的通道不在路由表里 ⇒ 计划抛（{@code CM12}）。 */
    public static ConsumptionRig rocketMqWithUnroutedListener() {
        return new ConsumptionRig(MqProvider.ROCKETMQ, true);
    }

    public GenericApplicationContext context() {
        return context;
    }

    /**
     * 某个组的录制消费者（{@code CM} 组经它读 {@code calls()}/{@code subscriptions()}/{@code settings()}，
     * {@code LM} 组经它 {@code deliver(...)}）。
     *
     * <p>没建过这个组 ⇒ 报<b>组名与实际建过的名单</b>而不是下标越界：{@code CM3}–{@code CM5} 的失败信息里
     * 必须能直接看出分流错在哪一组，否则一条"通道串组"的失败读起来跟"夹具坏了"没区别。
     */
    public RecordingRocketMqPushConsumer consumer(String group) {
        return factory.created().stream()
                .filter(created -> created.consumerGroup().equals(group))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有为组 " + group + " 创建消费者，实建："
                        + factory.created().stream().map(RecordingRocketMqPushConsumer::consumerGroup).toList()));
    }

    /**
     * 某条通道被业务方法调了几次（{@code LM4} 的"third 一次都没被调"、{@code LM5} 的"计数 0"）。
     *
     * <p>按 {@code channel()} 计数而不是按列表下标：有两条用例从两个线程投递，写入顺序由线程决定。
     */
    public int countFor(String channel) {
        return (int) received.stream().filter(message -> message.channel().equals(channel)).count();
    }

    /**
     * 消费者工厂的录制替身：{@code CM} 组的"每组一个消费者""重启建新实例"两条，
     * 判据都是<b>这里被调了几次、每次拿到哪个组</b>。
     *
     * <p><b>{@link RecordingRocketMqPushConsumer#failNextStartWith} 的注入点挂在这里</b>：消费者<b>只</b>在
     * {@code manager.start()} 里被创建，用例没有别的时机能在"创建之后、启动之前"碰到它。这条缝把注入点
     * 移到创建时，于是 {@code CM7} 断"启动失败的那个被关掉"、{@code CM8} 断"先启动成功的那批被回滚"
     * 两句都不用改生产代码。按组名匹配是因为两条用例要注入的组不同（见类文档那张升序表）；实测可注入的
     * 失败原文是未声明的 {@code IllegalStateException: connect to 127.0.0.1:19876 failed}，
     * 所以传 {@code RuntimeException} 就够，不需要受检异常通道。
     */
    public static final class RecordingFactory implements RocketMqPushConsumerFactory {

        private final List<RecordingRocketMqPushConsumer> created = new ArrayList<>();
        private final Map<String, RuntimeException> startFailures = new TreeMap<>();
        private final Map<String, RuntimeException> closeFailures = new TreeMap<>();
        private final List<String> closeOrder;

        RecordingFactory(List<String> closeOrder) {
            this.closeOrder = closeOrder;
        }

        @Override
        public RocketMqPushConsumerAdapter create(String nameServer, String consumerGroup) {
            RecordingRocketMqPushConsumer consumer =
                    new RecordingRocketMqPushConsumer(nameServer, consumerGroup, closeOrder);
            RuntimeException failure = startFailures.remove(consumerGroup);
            if (failure != null) {
                consumer.failNextStartWith(failure);
            }
            RuntimeException closeFailure = closeFailures.remove(consumerGroup);
            if (closeFailure != null) {
                consumer.failNextCloseWith(closeFailure);
            }
            created.add(consumer);
            return consumer;
        }

        /** 这个组的消费者下一次 {@code start()} 抛这个异常。 */
        public void failStartOf(String consumerGroup, RuntimeException failure) {
            startFailures.put(consumerGroup, failure);
        }

        /**
         * 这个组的消费者下一次 {@code close()} 抛这个异常（CM10 的"关闭失败不许盖住启动失败的原因"）。
         *
         * <p>同样只能挂在创建时注入：用例碰不到"已启动但还没被回滚关掉"的那一刻。
         */
        public void failCloseOf(String consumerGroup, RuntimeException failure) {
            closeFailures.put(consumerGroup, failure);
        }

        public List<RecordingRocketMqPushConsumer> created() {
            return created;
        }

        public int createCount() {
            return created.size();
        }
    }
}
