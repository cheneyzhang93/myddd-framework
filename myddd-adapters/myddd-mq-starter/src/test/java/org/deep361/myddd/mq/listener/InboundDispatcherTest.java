package org.deep361.myddd.mq.listener;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.common.trace.TraceContext;
import org.deep361.myddd.kernel.port.message.InboundMessage;
import org.deep361.myddd.kernel.port.message.MessageHeaderNames;
import org.deep361.myddd.mq.listener.fixture.MqContexts;
import org.deep361.myddd.mq.listener.fixture.ProbeListener;
import org.deep361.myddd.mq.listener.fixture.ShapeListeners;
import org.deep361.myddd.mq.payload.MessagePayloadConverter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * AB 组：适配器交给本类的只有"一条 InboundMessage"，本类还回去的只有"返回或抛出"——
 * 所以链路、参数形态、失败语义三件事都必须在这里钉死，且不许掺任何厂商概念。
 */
class InboundDispatcherTest {

    private final MessagePayloadConverter converter = new MessagePayloadConverter();

    @AfterEach
    void resetTraceContext() {
        // 消费者线程是复用线程：用例之间也不许残留上一次的 traceId
        TraceContext.clear();
    }

    @Test
    void should_hand_the_inbound_message_to_an_inbound_typed_listener() {
        GenericApplicationContext context = MqContexts.refresh(ProbeListener.class);
        InboundMessage message = inbound(ProbeListener.INBOUND_CHANNEL, "{}");

        assertThat(dispatcher(context).dispatch(message)).isEqualTo(1);

        ProbeListener probe = context.getBean(ProbeListener.class);
        assertThat(probe.inboundMessages()).hasSize(1);
        // 原样投递：不复制、不包装，监听器拿到的就是适配器构造的那一条
        assertThat(probe.inboundMessages().get(0)).isSameAs(message);
    }

    @Test
    void should_convert_the_payload_for_a_business_typed_listener() {
        GenericApplicationContext context = MqContexts.refresh(ProbeListener.class);

        dispatcher(context).dispatch(inbound(ProbeListener.PAYLOAD_CHANNEL,
                "{\"orderId\":\"o-1\",\"amount\":3,\"paidAt\":\"2026-09-20T00:00:00Z\"}"));

        assertThat(context.getBean(ProbeListener.class).payloads()).containsExactly(
                new ProbeListener.OrderPaid("o-1", 3, Instant.parse("2026-09-20T00:00:00Z")));
    }

    @Test
    void should_bind_the_trace_id_from_the_header_for_the_call_duration() {
        GenericApplicationContext context = MqContexts.refresh(ProbeListener.class);

        dispatcher(context).dispatch(traced(ProbeListener.INBOUND_CHANNEL, "trace-from-header"));

        assertThat(context.getBean(ProbeListener.class).tracesDuringCall()).containsExactly("trace-from-header");
    }

    @Test
    void should_restore_the_caller_trace_id_after_dispatch() {
        TraceContext.set("prev-trace");
        GenericApplicationContext context = MqContexts.refresh(ProbeListener.class);

        dispatcher(context).dispatch(traced(ProbeListener.INBOUND_CHANNEL, "trace-from-header"));

        // 消息里的 traceId 只在调用期间生效；结束后恢复原值而不是 clear——同进程内投递的假适配器还指着它
        assertThat(context.getBean(ProbeListener.class).tracesDuringCall()).containsExactly("trace-from-header");
        assertThat(TraceContext.currentTraceId()).isEqualTo("prev-trace");
    }

    @Test
    void should_clear_the_thread_context_when_the_caller_had_none() {
        GenericApplicationContext context = MqContexts.refresh(ProbeListener.class);
        assertThat(TraceContext.currentTraceId()).isNull();

        dispatcher(context).dispatch(traced(ProbeListener.INBOUND_CHANNEL, "trace-from-header"));

        // 原值为空才 clear：复用线程上下一条消息必须看不到这一条的链路
        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    void should_generate_a_trace_id_when_the_message_carries_none() {
        GenericApplicationContext context = MqContexts.refresh(ProbeListener.class);
        InboundDispatcher dispatcher = dispatcher(context);

        dispatcher.dispatch(inbound(ProbeListener.INBOUND_CHANNEL, "{}"));
        dispatcher.dispatch(inbound(ProbeListener.INBOUND_CHANNEL, "{}"));

        var traces = context.getBean(ProbeListener.class).tracesDuringCall();
        assertThat(traces).hasSize(2);
        assertThat(traces.get(0)).isNotBlank();
        // 每条没有上游链路的消息都是新链路的起点
        assertThat(traces.get(0)).isNotEqualTo(traces.get(1));
    }

    @Test
    void should_treat_a_blank_trace_header_as_absent() {
        GenericApplicationContext context = MqContexts.refresh(ProbeListener.class);

        // 空白头交给 TraceContext.set 会抛 IllegalArgumentException——上游把头写坏不该变成消费端崩
        dispatcher(context).dispatch(traced(ProbeListener.INBOUND_CHANNEL, "  "));

        assertThat(context.getBean(ProbeListener.class).tracesDuringCall().get(0)).isNotBlank();
    }

    @Test
    void should_fail_when_no_listener_cares_about_the_channel() {
        GenericApplicationContext context = MqContexts.refresh(ProbeListener.class);

        // 订阅关系应由注册表决定：走到这里说明适配器订阅了一个本进程无人监听的通道
        assertThatThrownBy(() -> dispatcher(context).dispatch(inbound("ghost-channel", "{}")))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("ghost-channel")
                .hasMessageContaining("order-events")
                .hasMessageContaining("order-paid");
        assertThat(context.getBean(ProbeListener.class).callCount()).isZero();
    }

    @Test
    void should_propagate_a_runtime_exception_from_the_listener_untouched() {
        GenericApplicationContext context = MqContexts.refresh(ShapeListeners.ThrowingHook.class);

        // 适配器的重试策略要看到业务异常本身（内核文档："方法抛出的异常视为消费失败"）
        assertThatThrownBy(() -> dispatcher(context)
                .dispatch(inbound(ShapeListeners.ThrowingHook.RUNTIME_CHANNEL, "{}")))
                .isSameAs(ShapeListeners.ThrowingHook.RUNTIME);
    }

    @Test
    void should_propagate_an_error_from_the_listener_untouched() {
        GenericApplicationContext context = MqContexts.refresh(ShapeListeners.ThrowingHook.class);

        assertThatThrownBy(() -> dispatcher(context)
                .dispatch(inbound(ShapeListeners.ThrowingHook.ERROR_CHANNEL, "{}")))
                .isSameAs(ShapeListeners.ThrowingHook.ERROR)
                .isNotInstanceOf(FrameworkException.class);
    }

    @Test
    void should_wrap_a_checked_exception_from_the_listener() {
        GenericApplicationContext context = MqContexts.refresh(ShapeListeners.ThrowingHook.class);
        Throwable wrapped = catchThrowable(() -> dispatcher(context)
                .dispatch(inbound(ShapeListeners.ThrowingHook.CHECKED_CHANNEL, "{}")));

        // 端口只声明 runtime、厂商回调不接受受检异常：包一层，但根因必须仍是监听器抛出的那一个实例
        assertThat(wrapped).isInstanceOf(FrameworkException.class).hasMessageContaining("throwingHook#onChecked");
        assertThat(wrapped.getCause()).isSameAs(ShapeListeners.ThrowingHook.CHECKED);
    }

    @Test
    void should_stop_at_the_first_failing_listener() {
        GenericApplicationContext context = MqContexts.refresh(ShapeListeners.FailFirstPair.class);

        assertThatThrownBy(() -> dispatcher(context).dispatch(inbound(ShapeListeners.FailFirstPair.CHANNEL, "{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("first-listener-failed");

        // 排序靠后的那条一次都不调用：整条消息重投是适配器的决定，这里不做"部分成功"的假象
        assertThat(context.getBean(ShapeListeners.FailFirstPair.class).invocations()).isEmpty();
    }

    @Test
    void should_invoke_every_listener_in_snapshot_order() {
        GenericApplicationContext context = MqContexts.refresh(ShapeListeners.SameChannelPair.class);
        MessageListenerRegistrar registrar = MqContexts.registrarOf(context);

        assertThat(dispatcher(context).dispatch(inbound(ShapeListeners.SameChannelPair.CHANNEL, "{}"))).isEqualTo(2);

        // 调用顺序就是快照顺序：分发不许另立一套顺序，否则"重投后谁先跑"又成了第二处真相
        assertThat(registrar.listenersFor(ShapeListeners.SameChannelPair.CHANNEL))
                .extracting(RegisteredMessageListener::id)
                .containsExactly("sameChannelPair#onA", "sameChannelPair#onB");
        assertThat(context.getBean(ShapeListeners.SameChannelPair.class).invocations()).containsExactly("onA", "onB");
    }

    @Test
    void should_fail_the_message_when_the_payload_cannot_be_read() {
        GenericApplicationContext context = MqContexts.refresh(ProbeListener.class);

        assertThatThrownBy(() -> dispatcher(context).dispatch(inbound(ProbeListener.PAYLOAD_CHANNEL, "{\"a\":")))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining(ProbeListener.OrderPaid.class.getName());

        assertThat(context.getBean(ProbeListener.class).callCount()).isZero();
        // 反序列化失败同样走 finally：坏消息不许把 traceId 留在复用线程上
        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    void should_reject_a_null_message() {
        MessageListenerRegistrar registrar = MqContexts.registrarOf(MqContexts.refresh(ProbeListener.class));
        InboundDispatcher dispatcher = new InboundDispatcher(registrar, converter);

        assertThatThrownBy(() -> dispatcher.dispatch(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("message");
        assertThatThrownBy(() -> new InboundDispatcher(null, converter))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("registrar");
        assertThatThrownBy(() -> new InboundDispatcher(registrar, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("converter");
    }

    private InboundDispatcher dispatcher(GenericApplicationContext context) {
        return new InboundDispatcher(MqContexts.registrarOf(context), converter);
    }

    private static InboundMessage inbound(String channel, String payload) {
        return new InboundMessage(channel, payload, "m-1", null);
    }

    private static InboundMessage traced(String channel, String traceId) {
        return new InboundMessage(channel, "{}", "m-1", Map.of(MessageHeaderNames.TRACE_ID, traceId));
    }
}
