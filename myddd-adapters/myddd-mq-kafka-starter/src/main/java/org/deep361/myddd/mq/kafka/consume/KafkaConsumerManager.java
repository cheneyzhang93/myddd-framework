package org.deep361.myddd.mq.kafka.consume;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.mq.MqProvider;
import org.deep361.myddd.mq.listener.InboundDispatcher;
import org.deep361.myddd.mq.route.MessageConsumptionPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Kafka 消费者的生命周期：把消费计划变成"每个消费组一个已在跑的容器"。
 *
 * <p><b>它是 {@link MessageConsumptionPlan} 的第二位调用方</b>（第一位是 Task 36 的 RocketMQ 侧），
 * 两条启动期快速失败与那边同源：① 有通道被监听却解析不出 provider ⇒ 计划抛 {@link FrameworkException}，
 * 应用起不来（CM12 钉"我们不改写它"）；② 一条通道都不属于 Kafka ⇒ <b>一个容器都不建</b>（CM1）。
 * 第二条是硬约束而不是优化：实测 {@code createContainer(new String[0])} 抛
 * {@code IllegalStateException: topics, topicPattern, or topicPartitions must be provided}，
 * 拿一个必然失败的实例换"看起来启动了"毫无价值。
 *
 * <p><b>为什么必须有一个持有者</b>：实测 Boot 的 {@code kafkaListenerContainerFactory} 本身
 * {@code instanceof SmartLifecycle == false}，而我们经工厂建出的容器又<b>不是 bean</b>（工厂在
 * {@code start()} 里调用，Spring 看不到那些实例），所以容器的起止只能由本类负责。本模块因此只有
 * 一个有状态对象，这是与 RocketMQ 侧对称的地方。
 *
 * <p><b>为什么一个组一个容器</b>：Kafka 的订阅以组为单位——同一条通道写进两个不同组的容器是
 * <b>两次投递</b>，写进同组两个容器则会互相分走分区。而内核 {@code @MessageListener#group()} 允许
 * 多条通道共用一个组、也允许都不写（空串）。按组分流因此是唯一形状：组 → 通道列表，一条通道只属于一个组。
 *
 * <p><b>缺省组读厂商键，不自建键</b>（R5）。空串组用 {@code spring.kafka.consumer.group-id} 补，读取缝是
 * {@code consumerFactory.getConfigurationProperties().get(ConsumerConfig.GROUP_ID_CONFIG)}——实测配了就是值、
 * 没配就是 {@code null}，而这份配置正是容器真正拿去建消费者的那份。读它就没有"框架算出的缺省组与厂商用的
 * 缺省组不一致"这种双真相。两处都没有 ⇒ CM6 那句点名 {@code @MessageListener} 与
 * {@code spring.kafka.consumer.group-id} 的 {@link FrameworkException}。
 *
 * <p><b>启动失败必须回滚</b>：一个已启动的容器留下 2 条<b>非守护</b>线程（消费者线程与
 * {@code ThreadPoolTaskScheduler}）。所以任何 {@code RuntimeException} 都要：停掉出错的那个，再停掉本批
 * 已启动的其余容器，最后抛一句带组、通道、厂商原文的 {@link FrameworkException}。<b>与 RocketMQ 侧相反</b>：
 * 实测容器 {@code stop()} 之后还能再 {@code start()}（换新线程），所以本类把容器缓存进 {@link #containers}
 * 并在重启时<b>复用</b>（CM10：重启不再建容器、工厂仍只被调一次），而不是每次启动重建。
 *
 * <p><b>阶段</b>：不覆盖 {@code getPhase()}/{@code isAutoStartup()}，用接口缺省（实测
 * {@code DEFAULT_PHASE == 2147483647}、{@code isAutoStartup()} 为 {@code true}）。容器的正确阶段就是
 * "最后启动、最先停止"：计划快照在 {@code afterSingletonsInstantiated} 冻结，而监听方法要能在一个完全就绪的应用里被调用。
 *
 * <p><b>线程安全</b>：{@code start()}/{@code stop()} 同步（Spring 生命周期回调本身单线程，这里同步是为了
 * "启动失败后仍可安全重入"这条路径清楚）；{@code running} 是 {@code volatile}，{@code isRunning()} 由容器
 * 线程之外的读取方调用。
 */
public final class KafkaConsumerManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerManager.class);

    /** 实测：消费者线程名 = beanName + 并发下标 + {@code -C-1}；线程名是消费侧唯一的现场指纹。 */
    private static final String BEAN_NAME_PREFIX = "myddd-kafka-";

    private final KafkaListenerContainerFactory<?> containerFactory;
    private final ConsumerFactory<?, ?> consumerFactory;
    private final MessageConsumptionPlan plan;
    private final InboundDispatcher dispatcher;
    /** 组 → 已建容器；跨 stop()/start() 保留以支持复用（CM10），创建顺序即插入顺序。 */
    private final Map<String, MessageListenerContainer> containers = new LinkedHashMap<>();
    /** 本批已启动、待停止的容器；{@code stop()}/回滚后清空，容器本体留在 {@link #containers}。 */
    private final List<MessageListenerContainer> started = new ArrayList<>();
    private volatile boolean running;

    /**
     * @param containerFactory Boot 的（或使用方贡献的）容器工厂，不可为 {@code null}
     * @param consumerFactory  消费者工厂，本类只经它读缺省组，不可为 {@code null}
     * @param plan             壳的消费计划，不可为 {@code null}
     * @param dispatcher       壳的入站分发器，不可为 {@code null}
     */
    public KafkaConsumerManager(KafkaListenerContainerFactory<?> containerFactory,
                                ConsumerFactory<?, ?> consumerFactory,
                                MessageConsumptionPlan plan,
                                InboundDispatcher dispatcher) {
        this.containerFactory = Objects.requireNonNull(containerFactory, "containerFactory 不能为 null");
        this.consumerFactory = Objects.requireNonNull(consumerFactory, "consumerFactory 不能为 null");
        this.plan = Objects.requireNonNull(plan, "plan 不能为 null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher 不能为 null");
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        Map<String, String> subscriptions = plan.subscriptionsFor(MqProvider.KAFKA);
        // 分组在空判断之前：空订阅的分组不读缺省组（containsValue("") 为 false ⇒ 直接给 null），
        // 而 CM1 要断"一条通道都不属于 Kafka 时缺省组一次都不读"，读缝因此必须在 return 之前完成。
        Map<String, List<String>> channelsByGroup = channelsByGroup(subscriptions);
        if (subscriptions.isEmpty()) {
            log.info("没有通道路由到 {}，Kafka 消费者容器不创建（本进程对 Kafka 只发不收）", MqProvider.KAFKA);
            running = true;
            return;
        }
        try {
            channelsByGroup.forEach(this::startGroup);
        } catch (RuntimeException failure) {
            // 不重新包装：startGroup 已给出含组与通道的 FrameworkException，再包一层就是两段重复的诊断。
            stopStarted();
            throw failure;
        }
        running = true;
        log.info("Kafka 消费者容器启动完成：{} 个消费组、{} 条通道", channelsByGroup.size(), subscriptions.size());
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
     * 组 → 通道列表。两层 {@code TreeMap} 保证顺序确定（实测计划来自 {@code Map.copyOf}，不保证迭代顺序，
     * 而容器创建顺序与日志顺序必须可复现，CM4 钉组升序、通道升序）。
     *
     * <p>缺省组<b>只在真的有空串组时</b>才去读工厂配置一次：全部通道都写了自己的组时，本类与
     * {@code spring.kafka.consumer.group-id} 完全无关，即使部署把它写成非法值也不该起不来
     * （CM5 钉"一次都不读"）。
     */
    private Map<String, List<String>> channelsByGroup(Map<String, String> subscriptions) {
        String defaultGroup = subscriptions.containsValue("") ? defaultGroup() : null;
        Map<String, List<String>> grouped = new TreeMap<>();
        new TreeMap<>(subscriptions).forEach((channel, group) -> grouped
                .computeIfAbsent(group.isBlank() ? defaultGroup : group, key -> new ArrayList<>())
                .add(channel));
        return grouped;
    }

    private String defaultGroup() {
        Object configured = consumerFactory.getConfigurationProperties().get(ConsumerConfig.GROUP_ID_CONFIG);
        if (configured == null) {
            throw new FrameworkException("有通道未写消费组，而缺省组也解析不出来（"
                    + ConsumerConfig.GROUP_ID_CONFIG + " 在消费者工厂的配置里是 null）。两个来源任选其一——"
                    + "给 @MessageListener 写 group，或配 spring.kafka.consumer.group-id。"
                    + "本框架不自建缺省组键：容器真正拿去建消费者的就是这份工厂配置，读它是唯一不产生第二个真相的位置");
        }
        String group = String.valueOf(configured);
        if (group.isBlank()) {
            throw new FrameworkException("缺省消费组不能是空白串（实际值：" + configured + "，类型 "
                    + configured.getClass().getName() + "）。它来自 spring.kafka.consumer.group-id"
                    + "（或 spring.kafka.consumer.properties.group.id），期望形态：非空白组名");
        }
        return group;
    }

    private void startGroup(String group, List<String> channels) {
        // 复用优先：组已建过容器就直接用（stop() 之后厂商容器实测可再 start()，见 CM10），
        // 工厂只在第一次遇到该组时被调用一次。
        MessageListenerContainer container =
                containers.computeIfAbsent(group, key -> containerFactory.createContainer(channels.toArray(String[]::new)));
        try {
            container.getContainerProperties().setGroupId(group);
            if (container instanceof BeanNameAware beanNameAware) {
                // 实测：设 beanName 之后 getListenerId() 就是它，消费者线程名跟着变成 myddd-kafka-<组>-0-C-1。
                beanNameAware.setBeanName(BEAN_NAME_PREFIX + group);
            }
            container.setupMessageListener(new KafkaMessageListener(dispatcher));
            container.start();
            started.add(container);
        } catch (RuntimeException failure) {
            // catch 包"设组、设 beanName、注册、启动"四步：启动成功的实例才进 started，
            // 出错的这一个由本层 stopQuietly 关掉；写反了就会出现"容器在跑，但框架不认识它"。
            stopQuietly(container);
            throw new FrameworkException("Kafka 消费者容器启动失败：consumer-group=" + group
                    + "，订阅通道=" + channels + "，厂商原因：" + vendorReason(failure), failure);
        }
    }

    private void stopStarted() {
        for (int index = started.size() - 1; index >= 0; index--) {
            stopQuietly(started.get(index));
        }
        started.clear();
    }

    /** 停止失败不能盖住启动失败的原因：这条路径只记日志。实测未 start 的容器 stop() 也不抛。 */
    private void stopQuietly(MessageListenerContainer container) {
        try {
            container.stop();
        } catch (RuntimeException failure) {
            log.warn("Kafka 消费者容器停止失败（忽略，启动失败的原因更重要）：{}", container, failure);
        }
    }

    /** 不切首行：spring-kafka 的实测原文一行到底，而配置类异常把非法值列在后续行（与 RocketMQ 侧刻意不同）。 */
    private static String vendorReason(Throwable failure) {
        String message = failure.getMessage();
        return message == null ? failure.getClass().getName()
                : failure.getClass().getSimpleName() + ": " + message;
    }
}
