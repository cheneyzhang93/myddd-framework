package org.deep361.myddd.mq.kafka.consume;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 厂商容器的录制替身：CM 组断言的是"框架对容器做了什么、按什么顺序做"，一行 broker 都不需要。
 *
 * <p><b>实现的是厂商接口，框架不自造缝</b>。{@code MessageListenerContainer} 的抽象方法只有
 * {@code setupMessageListener(Object)} 与 {@code metrics()}，{@code start/stop/isRunning} 从
 * {@code SmartLifecycle} 继承，{@code destroy()} 是接口缺省（体就是 {@code stop()}），其余 default 的方法体
 * 全是 {@code throw new UnsupportedOperationException(...)}。收益是<b>用例断言的对象与生产代码用的类型
 * 是同一个</b>——"缝与真实现漂移"这一类失败模式在此不存在（Task 36 必须自造缝，因为那边厂商给的是具体类）。
 *
 * <p><b>被覆写的那几个 default 不是随手挑的</b>：{@code getContainerProperties()}（设组必经，接口上没有
 * {@code setGroupId}）、{@code getGroupId()}（CM3/CM5 读回）、{@code getListenerId()}（返回 beanName，
 * 实测厂商在未显式设 listenerId 时给的就是 beanName）；{@code destroy()} <b>不</b>覆写——留着正好证明
 * "框架只调一次 {@code stop()} 就够"。
 *
 * <p><b>{@code ContainerProperties} 用厂商真对象</b>：CM13 要读回 {@code ackMode=BATCH}、
 * {@code pollTimeout=5000} 这些缺省值来证明"框架除组与 beanName 之外什么都没碰"，替一个假的属性类就给不出答案。
 * ⚠️ 实测 {@code new ContainerProperties(new String[0])} 抛 {@code IllegalArgumentException}，所以本类的构造子
 * 必须收到至少一条通道——这与"空计划一个容器都不建"（CM1）是同一件事的两面。
 *
 * <p><b>顺序日志</b>：{@code calls} 是<b>本容器专属</b>的那一条，工厂在产出本容器时已把 {@code create} 行
 * 播进它的开头，于是 {@code CM2} 能在一个 list 里读出"建容器 → 设 beanName → 注册监听 → 启动"这条
 * <b>跨两个对象</b>的顺序（工厂自己的全局 create 账在 {@link RecordingKafkaContainerFactory#calls()}，供 CM4 用）。
 */
public final class RecordingKafkaMessageListenerContainer implements MessageListenerContainer, BeanNameAware {

    private final ContainerProperties containerProperties;
    private final List<String> calls;
    private final List<RuntimeException> startFailures = new ArrayList<>();
    private final List<ConsumerRecord<Object, Object>> delivered = new ArrayList<>();
    private String beanName;
    private Object messageListener;
    private int startCount;
    private int stopCount;
    private boolean running;

    RecordingKafkaMessageListenerContainer(List<String> calls, String... channels) {
        this.calls = calls;
        this.containerProperties = new ContainerProperties(channels);
    }

    @Override
    public void setupMessageListener(Object messageListener) {
        this.messageListener = messageListener;
        // 也设进厂商真属性对象：CM13 读的是 getContainerProperties().getMessageListener()。
        containerProperties.setMessageListener(messageListener);
        calls.add("register");
    }

    @Override
    public void start() {
        // 组名快照进日志：CM2 要断的是"启动那一刻组已经设好"，而 setGroupId 走的是厂商真对象。
        calls.add("start group=" + containerProperties.getGroupId());
        if (!startFailures.isEmpty()) {
            throw startFailures.remove(0);
        }
        running = true;
        startCount++;
    }

    @Override
    public void stop() {
        calls.add("stop");
        stopCount++;
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public Map<String, Map<MetricName, ? extends Metric>> metrics() {
        return Map.of();
    }

    @Override
    public ContainerProperties getContainerProperties() {
        return containerProperties;
    }

    @Override
    public String getGroupId() {
        return containerProperties.getGroupId();
    }

    @Override
    public String getListenerId() {
        return beanName;
    }

    @Override
    public void setBeanName(String beanName) {
        this.beanName = beanName;
        calls.add("name " + beanName);
    }

    /** 下一次 {@link #start()} 抛这个异常，用来模拟厂商的未声明失败。 */
    public void failNextStartWith(RuntimeException failure) {
        startFailures.add(failure);
    }

    /** 本容器专属的顺序日志（开头一行是工厂播入的 {@code create}）。 */
    public List<String> calls() {
        return calls;
    }

    /** 容器实际订阅的通道（从厂商真对象读回，不是框架自己的记录）。 */
    public List<String> subscriptions() {
        return Arrays.asList(containerProperties.getTopics());
    }

    public String group() {
        return getGroupId();
    }

    public String beanName() {
        return beanName;
    }

    public Object listener() {
        return messageListener;
    }

    public int startCount() {
        return startCount;
    }

    public int stopCount() {
        return stopCount;
    }

    public List<ConsumerRecord<Object, Object>> delivered() {
        return delivered;
    }

    /**
     * "broker 投递一条记录"的等价物：直接调用框架注册进去的监听器，与厂商消费者线程里的调用同形。
     *
     * <p>未注册就投递 ⇒ {@code NullPointerException}，这是刻意的（CM2 钉注册早于启动）。
     * <b>本方法不往 {@code calls} 里写东西</b>——那张日志记的是"框架对厂商做了什么"，投递是厂商对框架做的事。
     */
    @SuppressWarnings("unchecked")
    public void deliver(ConsumerRecord<Object, Object> record) {
        delivered.add(record);
        ((MessageListener<Object, Object>) messageListener).onMessage(record);
    }
}
