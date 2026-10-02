package org.deep361.myddd.mq.rocketmq.consume;

import org.apache.rocketmq.remoting.protocol.heartbeat.SubscriptionData;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.mq.MqProvider;
import org.deep361.myddd.mq.listener.InboundDispatcher;
import org.deep361.myddd.mq.rocketmq.config.MydddRocketMqProperties;
import org.deep361.myddd.mq.route.MessageConsumptionPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * RocketMQ 消费者的生命周期：把消费计划变成"每个消费组一个已启动的消费者"。
 *
 * <p><b>它是 {@link MessageConsumptionPlan} 的第一位调用方</b>，这一点决定了启动期的两条快速失败：
 * ① 有通道被监听却解析不出 provider ⇒ 计划抛 {@code FrameworkException}，应用起不来（壳的 AC11 钉过语义，
 * 这里钉"真的有人在启动期调用它"）；② 一条通道都不属于 RocketMQ ⇒ 返回空 map ⇒ <b>一个消费者都不创建</b>。
 * 第二条不是优化而是必需：实测零订阅的消费者 {@code start()} 也会失败（厂商自动订阅
 * {@code %RETRY%<组>} 并去取路由），拿一个必然失败的实例换"看起来启动了"毫无价值。
 *
 * <p><b>为什么一个组一个消费者</b>：厂商的注册表以"组"为单位（同组第二个实例在启动期就被拒），
 * 而 {@code @MessageListener} 允许多个通道写同一个组；壳又保证同一通道不会有两个组
 * （注册表的 {@code LS13}）。按组分流因此是唯一形状：组 → 通道列表。
 *
 * <p><b>启动失败必须回滚</b>：实测 name-server 不可达时厂商抛<b>未声明的</b> {@code IllegalStateException}，
 * 并且留下 6 个<b>非守护</b>线程（{@code NettyClientSelector_1}、{@code NettyClientWorkerThread_1..4}、
 * {@code NettyEventExecutor}）——不清理就是"应用没起来，但 JVM 也不退出"。
 * 所以任何 {@code RuntimeException} 都要：关掉出错的这一个，再关掉本批已启动的其余消费者，
 * 最后抛一句带组、通道、name-server 的 {@code FrameworkException}。
 *
 * <p><b>阶段</b>：不覆盖 {@code getPhase()}/{@code isAutoStartup()}，用接口缺省
 * （实测 {@code SmartLifecycle.DEFAULT_PHASE == 2147483647}、{@code isAutoStartup()} 为 {@code true}）。
 * 消费者的正确阶段就是"最后启动、最先停止"：注册表快照在 {@code afterSingletonsInstantiated} 冻结，
 * 而监听方法要能在一个完全就绪的应用里被调用。
 *
 * <p><b>线程安全</b>：{@link #start()} 与 {@code stop()} 同步（Spring 生命周期回调本身是单线程，
 * 这里同步是为了"启动失败后仍可安全重入"这条路径清楚）；{@code running} 是 {@code volatile}，
 * {@code isRunning()} 由容器线程读取。
 */
public final class RocketMqConsumerManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RocketMqConsumerManager.class);

    /** 实测 {@code SubscriptionData.SUB_ALL == "*"}：内核注解没有 tag 概念，所以恒为全收。 */
    private static final String SUBSCRIBE_ALL = SubscriptionData.SUB_ALL;

    private final MydddRocketMqProperties properties;
    private final MessageConsumptionPlan plan;
    private final InboundDispatcher dispatcher;
    private final RocketMqPushConsumerFactory consumerFactory;
    private final List<RocketMqPushConsumerAdapter> started = new ArrayList<>();
    private volatile boolean running;

    /**
     * @param properties      本模块配置（name-server 与 {@code consumer.*} 三项）
     * @param plan            壳的消费计划，不可为 {@code null}
     * @param dispatcher      壳的入站分发器，不可为 {@code null}
     * @param consumerFactory 消费者工厂，不可为 {@code null}
     */
    public RocketMqConsumerManager(MydddRocketMqProperties properties, MessageConsumptionPlan plan,
                                   InboundDispatcher dispatcher, RocketMqPushConsumerFactory consumerFactory) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为 null");
        this.plan = Objects.requireNonNull(plan, "plan 不能为 null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher 不能为 null");
        this.consumerFactory = Objects.requireNonNull(consumerFactory, "consumerFactory 不能为 null");
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        Map<String, String> subscriptions = plan.subscriptionsFor(MqProvider.ROCKETMQ);
        if (subscriptions.isEmpty()) {
            log.info("没有通道路由到 {}，RocketMQ 消费者不启动（本进程对 RocketMQ 只发不收）", MqProvider.ROCKETMQ);
            running = true;
            return;
        }
        Map<String, List<String>> channelsByGroup = channelsByGroup(subscriptions);
        try {
            channelsByGroup.forEach(this::startGroup);
        } catch (RuntimeException failure) {
            // 不重新包装：startGroup 已经给出含组与通道的 FrameworkException，再包一层就是两段重复的诊断。
            stopStarted();
            throw failure;
        }
        running = true;
        log.info("RocketMQ 消费者启动完成：{} 个消费组、{} 条通道", channelsByGroup.size(), subscriptions.size());
    }

    @Override
    public synchronized void stop() {
        stopStarted();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 组 → 通道列表。两层 {@code TreeMap} 保证顺序确定：
     * 实测计划的返回值来自 {@code Map.copyOf}，<b>不保证迭代顺序</b>，
     * 而订阅顺序与日志顺序必须可复现（CM4 钉通道升序、CM3 钉组升序）。
     */
    private Map<String, List<String>> channelsByGroup(Map<String, String> subscriptions) {
        String defaultGroup = properties.getConsumer().getGroup();
        Map<String, List<String>> grouped = new TreeMap<>();
        new TreeMap<>(subscriptions).forEach((channel, group) -> grouped
                .computeIfAbsent(group.isBlank() ? defaultGroup : group, key -> new ArrayList<>())
                .add(channel));
        return grouped;
    }

    private void startGroup(String group, List<String> channels) {
        MydddRocketMqProperties.Consumer consumer = properties.getConsumer();
        RocketMqPushConsumerAdapter adapter = consumerFactory.create(properties.getNameServer(), group);
        try {
            adapter.configure(consumer.getMaxReconsumeTimes(), consumer.getConsumeTimeout().toMinutes());
            adapter.registerMessageListener(new RocketMqMessageListener(dispatcher, group));
            channels.forEach(channel -> adapter.subscribe(channel, SUBSCRIBE_ALL));
            adapter.start();
            started.add(adapter);
        } catch (RuntimeException failure) {
            // catch 只包"建、配、注册、订阅、启动"五步：启动成功的实例必须留在名单里，
            // 由外层回滚或 stop() 负责关——写反了就会出现"消费者在跑，但框架不认识它"。
            closeQuietly(adapter);
            throw new FrameworkException("RocketMQ 消费者启动失败：consumer-group=" + group + "，订阅通道=" + channels
                    + "，name-server=" + properties.getNameServer() + "，厂商原因：" + vendorReason(failure), failure);
        }
    }

    private void stopStarted() {
        for (int index = started.size() - 1; index >= 0; index--) {
            closeQuietly(started.get(index));
        }
        started.clear();
    }

    /** 关闭失败不能盖住启动失败的原因：这条路径只记日志。 */
    private void closeQuietly(RocketMqPushConsumerAdapter adapter) {
        try {
            adapter.close();
        } catch (RuntimeException failure) {
            log.warn("RocketMQ 消费者关闭失败（忽略，启动失败的原因更重要）：{}", adapter, failure);
        }
    }

    /** 带异常类名：厂商未声明的 {@code IllegalStateException} 只有一句 "connect to … failed"，没有类名用户分不清"没连上"和"状态机不对"。 */
    private static String vendorReason(Throwable failure) {
        String message = failure.getMessage();
        return message == null ? failure.getClass().getName()
                : failure.getClass().getSimpleName() + ": " + message;
    }
}
