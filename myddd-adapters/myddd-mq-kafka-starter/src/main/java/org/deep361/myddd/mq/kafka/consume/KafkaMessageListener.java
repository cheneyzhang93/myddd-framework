package org.deep361.myddd.mq.kafka.consume;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.mq.kafka.KafkaWireFormat;
import org.deep361.myddd.mq.listener.InboundDispatcher;
import org.springframework.kafka.listener.MessageListener;

import java.util.Objects;

/**
 * 厂商投递 → 壳的 {@link InboundDispatcher}：本类只有一条语句，并且<b>一个 {@code catch} 都没有</b>。
 *
 * <p><b>"不捕获"是设计，不是省略</b>。Task 36 的同类必须 {@code catch (RuntimeException)} 并返回
 * {@code RECONSUME_LATER}，因为 RocketMQ 的回调契约用<b>返回值</b>表达成败——异常递回厂商线程池，
 * 得到的只是一句日志和一条再也不会来的消息。Kafka 侧的契约正好相反：容器的错误处理入口是
 * {@code CommonErrorHandler.handleOne(Exception, ConsumerRecord, Consumer, MessageListenerContainer)}
 * 这类方法（入参类型是 {@code Exception}），厂商的缺省 {@code DefaultErrorHandler} 靠监听器<b>抛出</b>
 * 才会做 {@code seekAfterError} 与重投。在这里加一个 {@code catch} 并把异常吞掉，语义上等于把已经失败
 * 的消息判成成功：容器照常提交 offset，该记录被跳过。<b>LM2 钉的就是"异常原样外抛"</b>。
 *
 * <p><b>{@code Error} 同样外抛</b>：本方法不声明 {@code throws}，也不接 {@code Throwable}。厂商 handler
 * 的入参类型是 {@code Exception}，所以 {@code StackOverflowError} 不会被交给重投逻辑，而是冲出容器线程
 * （LM3 钉：栈已耗尽，框架没有资格判断这条消息能不能再试一次）。
 *
 * <p><b>类型选择 {@code MessageListener<Object, Object>}</b>（它本身不加方法，只有一个继承来的
 * {@code onMessage(T)}）：单条、无 {@code Acknowledgment}、无 {@code Consumer} 句柄。这三个"没有"是
 * 框架"不接管提交、不接管重投"的<b>类型级</b>保证——只要不实现 {@code ConsumerAwareMessageListener}、
 * 不实现 {@code BatchMessageListener}、参数里不出现 {@code Acknowledgment}，容器就没有任何途径让我们
 * 点头提交（LM6 用两条 {@code instanceof} 断它）。泛型实参取 {@code Object} 而不是 {@code String}：
 * 声明成 {@code String} 会在有人换掉 {@code value-deserializer} 时把问题变成 {@code ClassCastException}；
 * 这里让它走到 {@link KafkaWireFormat} 那句点名 {@code spring.kafka.consumer.value-deserializer} 的
 * {@link FrameworkException}。
 *
 * <p><b>阶段与线程</b>：本类无可变状态（唯一的字段是 {@code final} 的分发器，其 javadoc 自己声明线程安全），
 * 厂商的消费者线程池可并发调用它（LM5 钉）。
 */
public final class KafkaMessageListener implements MessageListener<Object, Object> {

    private final InboundDispatcher dispatcher;

    /**
     * @param dispatcher 壳的入站分发器，不可为 {@code null}
     */
    public KafkaMessageListener(InboundDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher 不能为 null");
    }

    /**
     * 把一条记录变成内核消息并分发。
     *
     * @param record 厂商入站记录（实测容器在 {@code poll} 到零条时不调用本方法，因此不需要判空参数）
     * @throws NullPointerException 入参为 {@code null}（消息由内核 record 与 {@code Objects} 给出）
     * @throws FrameworkException  墓碑记录或载荷类型不对（{@code KafkaWireFormat}）、该通道无人监听（{@code InboundDispatcher}）、载荷反序列化失败
     * @throws RuntimeException    业务监听方法自己的异常，原样传播（重投节奏由厂商 handler 决定）
     */
    @Override
    public void onMessage(ConsumerRecord<Object, Object> record) {
        dispatcher.dispatch(KafkaWireFormat.toInboundMessage(record));
    }
}
