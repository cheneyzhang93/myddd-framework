package org.deep361.myddd.mq.rocketmq.consume;

import org.apache.rocketmq.remoting.protocol.heartbeat.SubscriptionData;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RocketMqConsumerManager} 的启动账（CM1–CM12）：消费计划变成"每个消费组一个已启动的消费者"这条路径。
 *
 * <p>夹具是 {@link ConsumptionRig}——注册表、分发器、消费计划<b>都是生产类型</b>，替身只到厂商消费者为止，
 * 所以这里断言的每一句都落在"框架对厂商做了什么"或"业务方法收到了什么"上，一行 broker 都不需要（§0.3-7）。
 *
 * <p><b>组名升序是本类的骨架</b>：{@code channelsByGroup} 用两层 {@code TreeMap}，因为计划的返回值来自
 * {@code Map.copyOf}（实测不保证迭代顺序）。升序一旦写歪，{@code CM7}/{@code CM8} 注入失败的两个组
 * （第一个与最后一个）就会互换判据，而 {@code CM9} 的关闭顺序断言直接失义。
 *
 * <p><b>失败注入的位置</b>：消费者只在 {@code start()} 里被创建，用例没有别的时机能在"创建之后、启动之前"
 * 碰到它，所以注入点挂在工厂上（见 {@link ConsumptionRig.RecordingFactory}）。
 */
class RocketMqConsumerManagerTest {

    private final ConsumptionRig rig = ConsumptionRig.rocketMq();

    @Test
    void should_create_no_consumer_when_no_channel_routes_to_rocketmq() {
        // CM1：只发不收的部署是合法形态。零订阅的消费者 start() 实测也会失败，所以"一个都不建"不是优化而是必需。
        ConsumptionRig kafka = ConsumptionRig.kafkaOnly();
        kafka.manager.start();

        assertThat(kafka.factory.createCount()).isZero();
        assertThat(kafka.factory.created()).isEmpty();
        assertThat(kafka.manager.isRunning()).isTrue();     // 不建消费者≠没启动：生命周期仍然要能走通
        kafka.manager.stop();
        assertThat(kafka.manager.isRunning()).isFalse();
    }

    @Test
    void should_wire_one_group_in_the_vendors_own_call_order() {
        // CM2：configure→register→subscribe→start 的先后。注册必须早于启动（厂商启动后拒绝注册），
        // 而这条顺序无法由最终状态反推——订阅与启动各自都留下可观察物，顺序没有。
        rig.manager.start();

        assertThat(rig.consumer("g-one").calls()).containsExactly(
                "create g-one",
                "configure 16 15",
                "register",
                "subscribe a-channel *",
                "subscribe b-channel *",
                "subscribe c-channel *",
                "start");
        assertThat(rig.consumer("g-one").nameServer()).isEqualTo(ConsumptionRig.NAME_SERVER);
        assertThat(rig.consumer("g-one").listener()).isInstanceOf(RocketMqMessageListener.class);
        assertThat(rig.consumer("g-one").startCount()).isEqualTo(1);
    }

    @Test
    void should_give_each_group_exactly_its_own_channels_and_create_groups_in_ascending_order() {
        // CM3：通道不串组。十个通道分五组，每条通道只能出现在自己那一组的订阅清单里。
        rig.manager.start();

        assertThat(rig.factory.created()).extracting(RecordingRocketMqPushConsumer::consumerGroup)
                .containsExactly("alpha", "g-fail", "g-one", "myddd-consumer", "zeta");
        Map<String, List<String>> channelsByGroup = new TreeMap<>();
        rig.factory.created().forEach(created ->
                channelsByGroup.put(created.consumerGroup(), topics(created)));
        assertThat(channelsByGroup).containsOnly(
                Map.entry("alpha", List.of("ch-alpha")),
                Map.entry("g-fail", List.of("boom", "err", "first", "third")),
                Map.entry("g-one", List.of("a-channel", "b-channel", "c-channel")),
                Map.entry("myddd-consumer", List.of("ch-default")),
                Map.entry("zeta", List.of("ch-zeta")));
    }

    @Test
    void should_subscribe_channels_in_ascending_order_with_a_full_tag_expression() {
        // CM4：组内通道升序 + 订阅表达式恒为 SUB_ALL（实测 "*"，内核注解没有 tag 维度）。
        rig.manager.start();

        assertThat(rig.consumer("g-fail").subscriptions()).extracting(topicOf()).containsExactly("boom", "err", "first", "third");
        assertThat(rig.consumer("g-one").subscriptions()).extracting(topicOf()).containsExactly("a-channel", "b-channel", "c-channel");
        assertThat(rig.consumer("g-fail").subscriptions()).extracting(RecordingRocketMqPushConsumer.Subscription::subExpression)
                .containsOnly(SubscriptionData.SUB_ALL);
    }

    @Test
    void should_route_a_channel_without_a_group_to_the_configured_default_group() {
        // CM5：未写 group 的通道落进 myddd.mq.rocketmq.consumer.group，而不是厂商的 DEFAULT_CONSUMER
        // （后者所有应用共用，两个部署会互相分走对方的消息）。
        rig.manager.start();

        assertThat(rig.consumer("myddd-consumer").subscriptions()).extracting(topicOf()).containsExactly("ch-default");

        // 缺省组在启动期现取：改了配置再启动，落点必须跟着改（把配置读进字段缓存起来的变异死在这半段）。
        ConsumptionRig renamed = ConsumptionRig.rocketMq();
        renamed.properties.getConsumer().setGroup("g-mine");
        renamed.manager.start();
        assertThatThrownBy(() -> renamed.consumer("myddd-consumer")).isInstanceOf(AssertionError.class);
        assertThat(renamed.consumer("g-mine").subscriptions()).extracting(topicOf()).containsExactly("ch-default");
    }

    @Test
    void should_read_the_consumer_settings_at_start_instead_of_caching_them() {
        // CM6：max-reconsume-times 与 consume-timeout（**分钟**，实测厂商 getConsumeTimeout() 是 long 分钟）
        // 的缺省值先钉住，改成 3/1 分钟后重启必须落到厂商调用上——读一次缓存进字段的变异死在后半段。
        rig.manager.start();
        assertThat(rig.consumer("g-one").settings())
                .isEqualTo(new RecordingRocketMqPushConsumer.Settings(16, 15));

        rig.manager.stop();
        rig.properties.getConsumer().setMaxReconsumeTimes(3);
        rig.properties.getConsumer().setConsumeTimeout(Duration.ofMinutes(1));
        rig.manager.start();

        assertThat(rig.factory.created()).hasSize(10);
        assertThat(rig.factory.created().subList(5, 10))
                .allSatisfy(created -> assertThat(created.settings())
                        .isEqualTo(new RecordingRocketMqPushConsumer.Settings(3, 1)));
    }

    @Test
    void should_fail_fast_naming_group_channels_name_server_and_vendor_reason() {
        // CM7：升序第一个组启动失败 ⇒ 一句带全部上下文的 FrameworkException，且不往后再建第二个消费者。
        rig.factory.failStartOf("alpha", new IllegalStateException("connect to 127.0.0.1:19876 failed"));

        assertThatThrownBy(rig.manager::start)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("RocketMQ 消费者启动失败")
                .hasMessageContaining("consumer-group=alpha")
                .hasMessageContaining("订阅通道=[ch-alpha]")
                .hasMessageContaining("name-server=" + ConsumptionRig.NAME_SERVER)
                .hasMessageContaining("IllegalStateException: connect to 127.0.0.1:19876 failed")
                // 不重新包装：startGroup 已经给全了上下文，再包一层就是两段重复的诊断（cause 必须是厂商原异常）。
                .hasCauseInstanceOf(IllegalStateException.class);

        assertThat(rig.factory.createCount()).isEqualTo(1);
        assertThat(rig.consumer("alpha").closeCount()).isEqualTo(1);   // 出错的那一个被关掉，不留半个实例
        assertThat(rig.consumer("alpha").startCount()).isZero();
        assertThat(rig.manager.isRunning()).isFalse();
    }

    @Test
    void should_name_the_vendor_exception_class_when_it_carries_no_message() {
        // 厂商的未声明 IllegalStateException 常常没有消息（实测 RemotingConnectException 被吞掉那一层）。
        // 没有类名用户分不清"没连上"和"状态机不对"，所以 vendorReason 的 getName() 分支必须走到。
        rig.factory.failStartOf("alpha", new IllegalStateException());

        assertThatThrownBy(rig.manager::start)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("厂商原因：java.lang.IllegalStateException");
    }

    @Test
    void should_roll_back_the_consumers_that_already_started() {
        // CM8：升序最后一个组失败 ⇒ 前四个已启动的消费者全部关掉（实测厂商启动失败会留下 6 个非守护线程，
        // 不清理就是"应用没起来，但 JVM 也不退出"）。
        rig.factory.failStartOf("zeta", new IllegalStateException("connect to 127.0.0.1:19876 failed"));

        assertThatThrownBy(rig.manager::start)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("consumer-group=zeta");

        assertThat(rig.factory.createCount()).isEqualTo(5);
        assertThat(rig.factory.created()).allSatisfy(created -> {
            if ("zeta".equals(created.consumerGroup())) {
                assertThat(created.startCount()).isZero();     // 失败的那个压根没启动成功
            } else {
                assertThat(created.startCount()).isEqualTo(1);
            }
            assertThat(created.closeCount()).isEqualTo(1);     // 回滚覆盖每一个，含出错的那一个
        });
        // 出错的那个先关（startGroup 的 catch 里 closeQuietly），外层 stopStarted() 再逆序关本批已启动的四个：
        // "zeta" 排在表首正是"五步的 catch 不含 started.add"那条形状的可观察结果。
        assertThat(rig.closeOrder).containsExactly("zeta", "myddd-consumer", "g-one", "g-fail", "alpha");
        assertThat(rig.manager.isRunning()).isFalse();
    }

    @Test
    void should_close_consumers_in_reverse_start_order() {
        // CM9：stop() 逆序关闭——最后启动的最先关，与厂商的注册顺序对称（组名升序建，降序关）。
        rig.manager.start();
        rig.manager.stop();

        assertThat(rig.closeOrder).containsExactly("zeta", "myddd-consumer", "g-one", "g-fail", "alpha");
        assertThat(rig.factory.created()).allSatisfy(created -> assertThat(created.closeCount()).isEqualTo(1));
        assertThat(rig.manager.isRunning()).isFalse();
    }

    @Test
    void should_swallow_a_close_failure_instead_of_masking_the_start_failure() {
        // CM10：关闭失败只记日志。两件事同时发生时，递出去的原因必须是"为什么没起来"，不是"关不掉"。
        rig.factory.failStartOf("zeta", new IllegalStateException("connect to 127.0.0.1:19876 failed"));
        rig.factory.created();                                  // 空清单：下面的断言不能把顺序搞反
        ConsumptionRig.RecordingFactory factory = rig.factory;
        factory.failCloseOf("alpha", new IllegalStateException("关不掉"));

        assertThatThrownBy(rig.manager::start)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("consumer-group=zeta")
                .hasMessageNotContaining("关不掉");

        assertThatCode(rig.manager::stop).doesNotThrowAnyException();
        assertThat(rig.consumer("alpha").closeCount()).isEqualTo(1);   // 抛过异常也算关过
    }

    @Test
    void should_swallow_a_close_failure_during_stop() {
        // CM10 的另一半：单纯 stop() 时关闭失败不许外抛，否则 Spring 的销毁回调会被一个无关异常打断。
        rig.manager.start();
        rig.factory.created().forEach(created ->
                created.failNextCloseWith(new IllegalStateException("关不掉")));

        assertThatCode(rig.manager::stop).doesNotThrowAnyException();
        assertThat(rig.factory.created()).allSatisfy(created -> assertThat(created.closeCount()).isEqualTo(1));
    }

    @Test
    void should_start_only_once_and_tolerate_a_redundant_call() {
        // CM11：running 位挡住重复启动——同组第二个实例在厂商注册表里会被拒，重复 start 就是自杀。
        rig.manager.start();
        rig.manager.start();

        assertThat(rig.factory.createCount()).isEqualTo(5);
        assertThat(rig.factory.created()).allSatisfy(created -> assertThat(created.startCount()).isEqualTo(1));

        rig.manager.stop();
        assertThatCode(rig.manager::stop).doesNotThrowAnyException();
        assertThat(rig.closeOrder).hasSize(5);                  // 名单已清空：第二次 stop 不再关第二遍
    }

    @Test
    void should_build_new_consumer_instances_on_restart() {
        // 重启必须建新实例：厂商的实例名带 nanoTime，复用旧对象等于复用一个已经 shutdown 的状态机。
        rig.manager.start();
        rig.manager.stop();
        rig.manager.start();

        assertThat(rig.factory.createCount()).isEqualTo(10);
        assertThat(rig.closeOrder).containsExactly("zeta", "myddd-consumer", "g-one", "g-fail", "alpha");
        assertThat(rig.factory.created()).allSatisfy(created -> assertThat(created.startCount()).isEqualTo(1));
        assertThat(rig.manager.isRunning()).isTrue();
    }

    @Test
    void should_stop_without_a_prior_start() {
        // 上下文启动失败时 Spring 会对已创建的 bean 挨个回调销毁：这里必须一声不响。
        assertThatCode(rig.manager::stop).doesNotThrowAnyException();
        assertThat(rig.factory.createCount()).isZero();
        assertThat(rig.manager.isRunning()).isFalse();
    }

    @Test
    void should_refuse_to_start_when_a_monitored_channel_has_no_route() {
        // CM12：有条被监听的通道解析不出 provider ⇒ 计划在启动期就抛，应用起不来。
        // 这条钉的是"真有人调用计划"：壳的 AC11 钉过语义，没有这条就只是文档里的一句话。
        ConsumptionRig unrouted = ConsumptionRig.rocketMqWithUnroutedListener();

        assertThatThrownBy(unrouted.manager::start)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining(ConsumptionRig.Unrouted.CHANNEL)
                .hasMessageContaining("解析不出 provider");

        assertThat(unrouted.factory.createCount()).isZero();
        assertThat(unrouted.manager.isRunning()).isFalse();
    }

    @Test
    void should_deliver_each_group_to_its_own_listeners() {
        // CM3 的运行期那一半：从 g-one 的消费者投递只能进 g-one 的监听方法，通道不串组。
        rig.manager.start();
        List<String> receivedChannels = new ArrayList<>();

        rig.consumer("g-one").deliver(RecordingRocketMqPushConsumer.wire("b-channel", "{\"id\":2}"));
        rig.consumer("alpha").deliver(RecordingRocketMqPushConsumer.wire("ch-alpha", "{\"id\":3}"));
        rig.consumer("myddd-consumer").deliver(RecordingRocketMqPushConsumer.wire("ch-default", "{\"id\":4}"));
        rig.received.forEach(message -> receivedChannels.add(message.channel()));

        assertThat(receivedChannels).containsExactly("b-channel", "ch-alpha", "ch-default");
        assertThat(rig.countFor("a-channel")).isZero();
    }

    @Test
    void should_register_one_listener_per_group_and_not_share_it() {
        // 监听回调里带着组名（诊断日志用），共享一个实例就会把组名钉死在第一个组上。
        rig.manager.start();

        assertThat(rig.factory.created()).allSatisfy(created ->
                assertThat(created.listener()).isInstanceOf(RocketMqMessageListener.class));
        assertThat(rig.consumer("alpha").listener()).isNotSameAs(rig.consumer("zeta").listener());
    }

    @Test
    void should_keep_the_lifecycle_interfaces_defaults() {
        // 阶段与自动启动都用接口缺省（实测 DEFAULT_PHASE=2147483647、isAutoStartup()=true）：
        // 消费者的正确阶段就是"最后启动、最先停止"，因为注册表快照在 afterSingletonsInstantiated 才冻结。
        assertThat(rig.manager.getPhase()).isEqualTo(SmartLifecycle.DEFAULT_PHASE);
        assertThat(rig.manager.isAutoStartup()).isTrue();
        assertThat(rig.manager.isRunning()).isFalse();
    }

    @Test
    void should_reject_a_missing_collaborator() {
        assertThatThrownBy(() -> new RocketMqConsumerManager(null, rig.plan, rig.dispatcher, rig.factory))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("properties");
        assertThatThrownBy(() -> new RocketMqConsumerManager(rig.properties, null, rig.dispatcher, rig.factory))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("plan");
        // 每个参数一条：requireNonNull 只在第一个 null 上抛，写在同一次调用里就永远测不到后面的缝。
        assertThatThrownBy(() -> new RocketMqConsumerManager(rig.properties, rig.plan, null, rig.factory))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("dispatcher");
        assertThatThrownBy(() -> new RocketMqConsumerManager(rig.properties, rig.plan, rig.dispatcher, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("consumerFactory");
    }

    private static List<String> topics(RecordingRocketMqPushConsumer consumer) {
        return consumer.subscriptions().stream()
                .map(RecordingRocketMqPushConsumer.Subscription::topic)
                .toList();
    }

    private static java.util.function.Function<RecordingRocketMqPushConsumer.Subscription, String> topicOf() {
        return RecordingRocketMqPushConsumer.Subscription::topic;
    }
}
