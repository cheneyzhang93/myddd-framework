package org.deep361.myddd.mq.kafka.consume;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.listener.ContainerProperties;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 消费者生命周期组（CM1–CM14）：真壳（{@link ConsumptionRig}）+ 真 {@link KafkaConsumerManager}，
 * 工厂/容器一律替身（§0.3-7）——一行 broker 都不碰，全程在 {@code createContainer}/{@code start} 处打桩。
 */
class KafkaConsumerManagerTest {

    private static KafkaConsumerManager manager(ConsumptionRig rig, RecordingKafkaContainerFactory factory) {
        return new KafkaConsumerManager(factory, rig.consumerFactory, rig.plan, rig.dispatcher);
    }

    private static RecordingKafkaMessageListenerContainer byGroup(RecordingKafkaContainerFactory factory, String group) {
        return factory.created().stream()
                .filter(container -> group.equals(container.group()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有为组 " + group + " 建容器"));
    }

    @Test
    void should_create_no_container_when_no_channel_routes_to_kafka() {
        ConsumptionRig rig = ConsumptionRig.rocketMqOnly();
        RecordingKafkaContainerFactory factory = new RecordingKafkaContainerFactory();
        KafkaConsumerManager manager = manager(rig, factory);
        assertThatCode(manager::start).doesNotThrowAnyException();
        assertThat(factory.createCount()).isZero();
        assertThat(factory.calls()).isEmpty();
        assertThat(manager.isRunning()).isTrue();
        assertThatCode(manager::stop).doesNotThrowAnyException();
        // 空计划在分组之前就 return ⇒ 缺省组一次都不读。
        assertThat(rig.consumerFactory.configurationReads()).isZero();
    }

    @Test
    void should_run_create_name_register_start_in_that_order() {
        ConsumptionRig rig = ConsumptionRig.kafka("g-from-props");
        RecordingKafkaContainerFactory factory = new RecordingKafkaContainerFactory();
        manager(rig, factory).start();
        // 跨两个对象（工厂 create + 容器 name/register/start）的顺序记在同一条容器专属账上。
        assertThat(byGroup(factory, "g-one").calls()).containsExactly(
                "create a-channel,b-channel,c-channel", "name myddd-kafka-g-one", "register", "start group=g-one");
    }

    @Test
    void should_create_one_container_per_group_and_keep_the_channels_apart() {
        ConsumptionRig rig = ConsumptionRig.kafka("g-from-props");
        RecordingKafkaContainerFactory factory = new RecordingKafkaContainerFactory();
        manager(rig, factory).start();
        assertThat(factory.createCount()).isEqualTo(5);
        assertThat(byGroup(factory, "alpha").subscriptions()).containsExactly("ch-alpha");
        assertThat(byGroup(factory, "g-fail").subscriptions()).containsExactly("boom", "err");
        assertThat(byGroup(factory, "g-from-props").subscriptions()).containsExactly("ch-default");
        assertThat(byGroup(factory, "g-one").subscriptions()).containsExactly("a-channel", "b-channel", "c-channel");
        assertThat(byGroup(factory, "zeta").subscriptions()).containsExactly("ch-zeta");
        // 任意两容器的通道集合不相交（串了组 = 两个组抢同一批分区记录）。
        Set<String> seen = new HashSet<>();
        for (RecordingKafkaMessageListenerContainer container : factory.created()) {
            for (String channel : container.subscriptions()) {
                assertThat(seen.add(channel)).as("通道 %s 落进了两个组", channel).isTrue();
            }
        }
    }

    @Test
    void should_visit_groups_and_channels_in_ascending_order() {
        ConsumptionRig rig = ConsumptionRig.kafka("g-from-props");
        RecordingKafkaContainerFactory factory = new RecordingKafkaContainerFactory();
        manager(rig, factory).start();
        // 计划来自 Map.copyOf 不保证迭代顺序；两层 TreeMap 校正后 create 行必须组升序、组内通道升序。
        assertThat(factory.calls()).containsExactly(
                "create ch-alpha", "create boom,err", "create ch-default",
                "create a-channel,b-channel,c-channel", "create ch-zeta");
    }

    @Test
    void should_take_the_group_from_the_annotation_and_the_factory_config_as_two_sources() {
        ConsumptionRig withDefault = ConsumptionRig.kafka("g-from-props");
        RecordingKafkaContainerFactory factory = new RecordingKafkaContainerFactory();
        manager(withDefault, factory).start();
        // 注解未写 group 的 ch-default 落在工厂缺省组；写了组的 ch-alpha 仍在自己的组。
        assertThat(byGroup(factory, "g-from-props").subscriptions()).containsExactly("ch-default");
        assertThat(byGroup(factory, "alpha").subscriptions()).containsExactly("ch-alpha");
        // 只有 1 条空组也只读一次，不是每通道一次。
        assertThat(withDefault.consumerFactory.configurationReads()).isEqualTo(1);
        // 反向半边：全显式组时本类与 spring.kafka.consumer.group-id 完全无关，一次都不读。
        ConsumptionRig allExplicit = ConsumptionRig.kafkaWithExplicitGroupsOnly("g-from-props");
        manager(allExplicit, new RecordingKafkaContainerFactory()).start();
        assertThat(allExplicit.consumerFactory.configurationReads()).isZero();
    }

    @Test
    void should_refuse_a_missing_default_group_before_creating_anything() {
        ConsumptionRig rig = ConsumptionRig.kafka(null);
        RecordingKafkaContainerFactory factory = new RecordingKafkaContainerFactory();
        KafkaConsumerManager manager = manager(rig, factory);
        assertThatThrownBy(manager::start)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("@MessageListener")
                .hasMessageContaining("spring.kafka.consumer.group-id");
        assertThat(factory.createCount()).isZero();
        assertThat(manager.isRunning()).isFalse();
        // 启动失败后仍可安全重入：再 start() 抛同形的一条。
        assertThatThrownBy(manager::start).isInstanceOf(FrameworkException.class);
    }

    @Test
    void should_translate_a_vendor_start_failure_and_stop_that_container() {
        ConsumptionRig rig = ConsumptionRig.kafka("g-from-props");
        RecordingKafkaContainerFactory factory = new RecordingKafkaContainerFactory();
        KafkaConsumerManager manager = manager(rig, factory);
        RuntimeException injected = new IllegalStateException("connect to 127.0.0.1:19092 failed");
        factory.failNextCreatedContainerStartWith(injected);
        assertThatThrownBy(manager::start)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("consumer-group=alpha")
                .hasMessageContaining("订阅通道=[ch-alpha]")
                .hasMessageContaining("IllegalStateException: connect to 127.0.0.1:19092 failed")
                .hasCauseReference(injected);
        RecordingKafkaMessageListenerContainer alpha = byGroup(factory, "alpha");
        assertThat(alpha.stopCount()).isEqualTo(1);
        assertThat(alpha.startCount()).isZero();
        // 第一个组失败后不再尝试其余组。
        assertThat(factory.createCount()).isEqualTo(1);
        assertThat(manager.isRunning()).isFalse();
    }

    @Test
    void should_rollback_containers_started_before_the_failing_group() {
        ConsumptionRig rig = ConsumptionRig.kafka("g-from-props");
        RecordingKafkaContainerFactory factory = new RecordingKafkaContainerFactory();
        KafkaConsumerManager manager = manager(rig, factory);
        manager.start();
        manager.stop();
        // 复用容器重启：把失败打在第二个创建的容器（g-fail）上，第一个（alpha）会被回滚。
        RuntimeException injected = new IllegalStateException("connect to 127.0.0.1:19092 failed");
        RecordingKafkaMessageListenerContainer first = factory.created().get(0);
        RecordingKafkaMessageListenerContainer second = factory.created().get(1);
        second.failNextStartWith(injected);
        assertThatThrownBy(manager::start)
                .isInstanceOf(FrameworkException.class)
                .hasCauseReference(injected);
        // 外层 catch 不重新包装（cause 是注入的实例本身）。
        assertThat(first.stopCount()).isEqualTo(2);
        // 失败的那个重启时 start() 抛出 ⇒ 没进 started、startCount 仍是 1。
        assertThat(second.startCount()).isEqualTo(1);
        assertThat(factory.createCount()).isEqualTo(5);
        assertThat(manager.isRunning()).isFalse();
    }

    @Test
    void should_be_idempotent_across_repeated_start_and_stop_calls() {
        ConsumptionRig rig = ConsumptionRig.kafka("g-from-props");
        RecordingKafkaContainerFactory factory = new RecordingKafkaContainerFactory();
        KafkaConsumerManager manager = manager(rig, factory);
        manager.start();
        manager.start();
        assertThat(factory.createCount()).isEqualTo(5);
        for (RecordingKafkaMessageListenerContainer container : factory.created()) {
            assertThat(container.startCount()).isEqualTo(1);
        }
        manager.stop();
        manager.stop();
        for (RecordingKafkaMessageListenerContainer container : factory.created()) {
            assertThat(container.stopCount()).isEqualTo(1);
        }
    }

    @Test
    void should_reuse_the_same_containers_when_restarted_after_a_stop() {
        ConsumptionRig rig = ConsumptionRig.kafka("g-from-props");
        RecordingKafkaContainerFactory factory = new RecordingKafkaContainerFactory();
        KafkaConsumerManager manager = manager(rig, factory);
        manager.start();
        manager.stop();
        manager.start();
        // 与 Task 36 的 CM10 判定相反，而且是实测逼出来的：stop() 之后能再 start() ⇒ 复用，工厂不再被调。
        assertThat(factory.createCount()).isEqualTo(5);
        for (RecordingKafkaMessageListenerContainer container : factory.created()) {
            assertThat(container.startCount()).isEqualTo(2);
        }
        assertThat(manager.isRunning()).isTrue();
    }

    @Test
    void should_keep_the_lifecycle_interface_defaults_and_honor_the_stop_callback() {
        ConsumptionRig rig = ConsumptionRig.kafka("g-from-props");
        RecordingKafkaContainerFactory factory = new RecordingKafkaContainerFactory();
        KafkaConsumerManager manager = manager(rig, factory);
        // 消费者要"最后起、最先停"，用接口缺省，一个都不覆盖。
        assertThat(manager.getPhase()).isEqualTo(SmartLifecycle.DEFAULT_PHASE);
        assertThat(manager.isAutoStartup()).isTrue();
        manager.start();
        AtomicBoolean notified = new AtomicBoolean();
        manager.stop(() -> notified.set(true));
        assertThat(notified).isTrue();
        for (RecordingKafkaMessageListenerContainer container : factory.created()) {
            assertThat(container.stopCount()).isEqualTo(1);
        }
    }

    @Test
    void should_create_nothing_when_the_plan_itself_fails() {
        ConsumptionRig rig = ConsumptionRig.kafkaWithUnroutedListener();
        RecordingKafkaContainerFactory factory = new RecordingKafkaContainerFactory();
        KafkaConsumerManager manager = manager(rig, factory);
        // 计划那句原样抛出（本类不再包装 ⇒ 不含 consumer-group=）。
        assertThatThrownBy(manager::start)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("nobody-routes-me")
                .hasMessageContaining("解析不出 provider")
                .hasMessageNotContaining("consumer-group=");
        assertThat(factory.createCount()).isZero();
        assertThat(manager.isRunning()).isFalse();
    }

    @Test
    void should_change_no_container_property_except_group_and_bean_name() {
        ConsumptionRig rig = ConsumptionRig.kafka("g-from-props");
        RecordingKafkaContainerFactory factory = new RecordingKafkaContainerFactory();
        manager(rig, factory).start();
        // 这是 Kafka 侧与 RocketMQ 侧最大的交付差异：框架只碰组与 beanName，调参面整个属于 spring.kafka.*。
        ContainerProperties properties = byGroup(factory, "g-one").getContainerProperties();
        assertThat(properties.getAckMode()).isEqualTo(ContainerProperties.AckMode.BATCH);
        assertThat(properties.getPollTimeout()).isEqualTo(5000L);
        assertThat(properties.isMissingTopicsFatal()).isFalse();
        assertThat(properties.getShutdownTimeout()).isEqualTo(10000L);
        assertThat(properties.getConsumerStartTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getAssignmentCommitOption())
                .isEqualTo(ContainerProperties.AssignmentCommitOption.LATEST_ONLY_NO_TX);
        assertThat(properties.isSyncCommits()).isTrue();
        assertThat(properties.getCommitRetries()).isEqualTo(3);
        assertThat(properties.getClientId()).isEmpty();
        assertThat(properties.getMessageListener()).isInstanceOf(KafkaMessageListener.class);
    }

    @Test
    void should_reject_each_missing_collaborator_by_name() {
        ConsumptionRig rig = ConsumptionRig.kafka("g");
        RecordingKafkaContainerFactory factory = new RecordingKafkaContainerFactory();
        assertThatThrownBy(() -> new KafkaConsumerManager(null, rig.consumerFactory, rig.plan, rig.dispatcher))
                .isInstanceOf(NullPointerException.class).hasMessage("containerFactory 不能为 null");
        assertThatThrownBy(() -> new KafkaConsumerManager(factory, null, rig.plan, rig.dispatcher))
                .isInstanceOf(NullPointerException.class).hasMessage("consumerFactory 不能为 null");
        assertThatThrownBy(() -> new KafkaConsumerManager(factory, rig.consumerFactory, null, rig.dispatcher))
                .isInstanceOf(NullPointerException.class).hasMessage("plan 不能为 null");
        assertThatThrownBy(() -> new KafkaConsumerManager(factory, rig.consumerFactory, rig.plan, null))
                .isInstanceOf(NullPointerException.class).hasMessage("dispatcher 不能为 null");
    }
}
