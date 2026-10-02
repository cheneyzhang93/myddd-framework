package org.deep361.myddd.mq.kafka.consume;

import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpoint;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.TopicPartitionOffset;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 容器工厂的录制替身：CM 组的"每组一个容器""重启复用同一容器"两条，
 * 判据都是<b>这里被调了几次、每次拿到哪些通道</b>。
 *
 * <p><b>三个不用来建容器的方法一律抛 {@code UnsupportedOperationException}，而不是返回 {@code null}</b>：
 * 那三条（按 {@code TopicPartitionOffset} 直接分配、按 {@code Pattern} 通配、{@code @KafkaListener} 端点）
 * 在框架的订阅模型里根本不存在——本框架的订阅关系只有一个来源，就是壳的消费计划。让它们响亮地失败，是为了
 * 哪天有人"顺手"把 manager 的调用点改成 {@code Pattern} 重载时，测试当场炸，而不是静默多订阅一批 topic。
 * 实测 {@code KafkaListenerContainerFactory} 的抽象面正好这四个方法。
 */
public final class RecordingKafkaContainerFactory implements KafkaListenerContainerFactory<MessageListenerContainer> {

    private final List<String> calls = new ArrayList<>();
    private final List<RecordingKafkaMessageListenerContainer> created = new ArrayList<>();
    private final List<RuntimeException> pendingStartFailures = new ArrayList<>();

    @Override
    public MessageListenerContainer createContainer(String... topics) {
        String createLine = "create " + String.join(",", topics);
        // 全局账（供 CM1/CM3/CM4 数创建次数与创建顺序）只记 create 行。
        calls.add(createLine);
        // 每个容器另起一条专属顺序账，并以自己的 create 行开头：CM2 因此在同一个 list 里读到
        // "建容器 → 设 beanName → 注册监听 → 启动"的跨对象顺序。
        List<String> containerCalls = new ArrayList<>();
        containerCalls.add(createLine);
        RecordingKafkaMessageListenerContainer container =
                new RecordingKafkaMessageListenerContainer(containerCalls, topics);
        if (!pendingStartFailures.isEmpty()) {
            container.failNextStartWith(pendingStartFailures.remove(0));
        }
        created.add(container);
        return container;
    }

    @Override
    public MessageListenerContainer createContainer(TopicPartitionOffset... topics) {
        throw new UnsupportedOperationException("本框架只按通道名订阅：按分区直接分配会绕过消费计划的通道名单");
    }

    @Override
    public MessageListenerContainer createContainer(Pattern topicPattern) {
        throw new UnsupportedOperationException("同上：Pattern 订阅等于在框架的订阅关系之外再立一套");
    }

    @Override
    public MessageListenerContainer createListenerContainer(KafkaListenerEndpoint endpoint) {
        throw new UnsupportedOperationException("@KafkaListener 的容器不经这里（Task 38 Step 0 第 4 条）");
    }

    /** 工厂的全局创建账（每次 {@code createContainer} 一行 {@code create}，用于计数与创建顺序）。 */
    public List<String> calls() {
        return calls;
    }

    /**
     * 下一个由本工厂产出的容器，它的 {@code start()} 抛这个异常——CM7/CM8 的注入缝。
     *
     * <p>为什么挂在工厂而不是让用例先拿到容器：容器<b>只</b>在 {@code manager.start()} 里被创建，
     * 用例没有别的时机能在"创建之后、启动之前"碰到它。
     */
    public void failNextCreatedContainerStartWith(RuntimeException failure) {
        pendingStartFailures.add(failure);
    }

    public List<RecordingKafkaMessageListenerContainer> created() {
        return created;
    }

    public int createCount() {
        return created.size();
    }
}
