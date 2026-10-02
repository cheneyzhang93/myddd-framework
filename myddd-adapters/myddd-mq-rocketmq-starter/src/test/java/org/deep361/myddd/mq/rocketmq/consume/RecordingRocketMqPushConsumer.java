package org.deep361.myddd.mq.rocketmq.consume;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 厂商消费者的录制替身：CM 组断言的是"框架对厂商做了什么"，一行 broker 都不需要。
 *
 * <p>{@link #deliver(MessageExt...)} 就是"broker 投递一条消息"的等价物——它直接调用框架注册进去的
 * {@code MessageListenerConcurrently}，与厂商线程池里的调用完全同形（同一个方法、同一组入参类型）。
 */
public final class RecordingRocketMqPushConsumer implements RocketMqPushConsumerAdapter {

    /** 一次订阅的观察。 */
    public record Subscription(String topic, String subExpression) {
    }

    /** 一次 {@link #configure} 的观察。 */
    public record Settings(int maxReconsumeTimes, long consumeTimeoutMinutes) {
    }

    private final String nameServer;
    private final String consumerGroup;
    private final List<Subscription> subscriptions = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();
    private final List<RuntimeException> startFailures = new ArrayList<>();
    private final List<RuntimeException> closeFailures = new ArrayList<>();
    /** 与同批其余消费者共写的关闭顺序表（{@code CM9} 读它；由 {@link ConsumptionRig.RecordingFactory} 传入）。 */
    private final List<String> closeOrder;
    private MessageListenerConcurrently listener;
    private Settings settings;
    private int startCount;
    private int closeCount;
    /**
     * 厂商递给回调的上下文：实测缺省 {@code delayLevelWhenNextConsume=0}、{@code ackIndex=2147483647}。
     *
     * <p>建一次而不是每次 {@code deliver} 新建——{@code LM6} 要断"框架在投递前后都没改这个实例上的任何字段"，
     * 每次新建的话"前后各读一次"读到的是两个对象，那条用例就断不出"没改"。
     */
    private final ConsumeConcurrentlyContext context =
            new ConsumeConcurrentlyContext(new MessageQueue("probe", "broker-a", 0));

    public RecordingRocketMqPushConsumer(String nameServer, String consumerGroup, List<String> closeOrder) {
        this.nameServer = nameServer;
        this.consumerGroup = consumerGroup;
        this.closeOrder = closeOrder;
        calls.add("create " + consumerGroup);
    }

    /**
     * 厂商动作的发生顺序（含 {@code create}、{@code configure}、{@code register}、逐条
     * {@code subscribe <topic>}、{@code start}、{@code close}）。
     *
     * <p>CM2 断言的是"注册早于启动、启动早于任何投递"这条顺序——它无法由"最终状态"反推
     * （订阅与启动都留下了各自的可观察物，顺序却没有），所以顺序必须由一次调用日志亲自记录。
     * 少这张日志，"把 {@code start()} 挪到订阅之前"这种变异就杀不掉，而厂商在启动后会拒绝新增订阅。
     */
    public List<String> calls() {
        return calls;
    }

    public String nameServer() {
        return nameServer;
    }

    public String consumerGroup() {
        return consumerGroup;
    }

    public List<Subscription> subscriptions() {
        return subscriptions;
    }

    public MessageListenerConcurrently listener() {
        return listener;
    }

    public Settings settings() {
        return settings;
    }

    public int startCount() {
        return startCount;
    }

    public int closeCount() {
        return closeCount;
    }

    /** 下一次 {@link #start()} 抛这个异常（用来模拟厂商的未声明 {@code IllegalStateException}）。 */
    public void failNextStartWith(RuntimeException failure) {
        startFailures.add(failure);
    }

    /** 下一次 {@link #close()} 抛这个异常（{@code CM10} 用它钉"关闭失败只记日志，不盖住别的原因"）。 */
    public void failNextCloseWith(RuntimeException failure) {
        closeFailures.add(failure);
    }

    @Override
    public void subscribe(String topic, String subExpression) {
        subscriptions.add(new Subscription(topic, subExpression));
        calls.add("subscribe " + topic + " " + subExpression);
    }

    @Override
    public void registerMessageListener(MessageListenerConcurrently listener) {
        this.listener = listener;
        calls.add("register");
    }

    @Override
    public void configure(int maxReconsumeTimes, long consumeTimeoutMinutes) {
        this.settings = new Settings(maxReconsumeTimes, consumeTimeoutMinutes);
        calls.add("configure " + maxReconsumeTimes + " " + consumeTimeoutMinutes);
    }

    @Override
    public void start() {
        calls.add("start");
        if (!startFailures.isEmpty()) {
            throw startFailures.remove(0);
        }
        startCount++;
    }

    @Override
    public void close() {
        calls.add("close");
        // 先记账再抛：closeQuietly 的 catch 分支要能证明"关闭确实被尝试过"，只抛不记就把这条用例变成哑弹。
        closeCount++;
        closeOrder.add(consumerGroup);
        if (!closeFailures.isEmpty()) {
            throw closeFailures.remove(0);
        }
    }

    /** 驱动厂商回调；零条消息也是合法入参（LM5 用它）。 */
    public ConsumeConcurrentlyStatus deliver(MessageExt... messages) {
        return listener.consumeMessage(List.of(messages), context);
    }

    /** 上面那个实例（LM6 要的就是"前后读同一个对象上的两个字段"）。 */
    public ConsumeConcurrentlyContext context() {
        return context;
    }

    /** 厂商入站消息的构造缝：topic + UTF-8 载荷 + 用户头（{@code headers} 里允许出现 {@code null} 值，见下）。 */
    public static MessageExt wire(String topic, String payload, Map<String, String> headers) {
        MessageExt message = new MessageExt();
        message.setTopic(topic);
        if (payload != null) {
            message.setBody(payload.getBytes(StandardCharsets.UTF_8));
        }
        message.setMsgId("msg-" + topic);
        headers.forEach((name, value) -> {
            if (value == null) {
                putRawProperty(message, name, null);       // 钉 WF8：null 值头不进 InboundMessage
            } else {
                message.putUserProperty(name, value);
            }
        });
        return message;
    }

    /** 只有 topic 与载荷的最简形态（headers 为空）。 */
    public static MessageExt wire(String topic, String payload) {
        return wire(topic, payload, Map.of());
    }

    /**
     * 带厂商系统属性的入站消息：走厂商自己的 {@code setKeys}/{@code setTags}，因此属性名真在
     * {@code MessageConst.STRING_HASH_SET} 里（WF6 断言读方向把它们摘干净）。
     */
    public static MessageExt wireWithSystemProperties(String topic, String payload, String keys, String tags,
                                                      Map<String, String> headers) {
        MessageExt message = wire(topic, payload, headers);
        message.setKeys(keys);
        message.setTags(tags);
        return message;
    }

    /**
     * 写入值为 {@code null} 的属性——<b>厂商的公开 API 做不到这件事</b>，一手实测：
     * {@code putUserProperty} 对 null/空白值抛 {@code IllegalArgumentException}，
     * 而 {@code setProperties} 是 package-private、{@code properties} 字段是 private（都在
     * {@code org.apache.rocketmq.common.message} 包里）。
     * 但 broker 解码路径（{@code MessageDecoder}）走的就是那条内部方法，属性表里出现 null 值是可达形态，
     * 而内核 {@code InboundMessage} 的构造对 null 值直接 NPE——所以 {@code RocketMqWireFormat.userHeaders}
     * 的"摘除 null 值"这一分支必须能用例钉住，反射是这个夹具唯一的非侵入手段（只在 test 源码，不进 jar）。
     */
    private static void putRawProperty(MessageExt message, String name, String value) {
        try {
            Field field = Message.class.getDeclaredField("properties");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, String> properties = (Map<String, String>) field.get(message);
            if (properties == null) {
                properties = new LinkedHashMap<>();   // 实测新建 MessageExt 的 properties 就是 null
                field.set(message, properties);
            }
            properties.put(name, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("夹具无法写入 null 值属性（厂商字段名变了？）", e);
        }
    }
}
