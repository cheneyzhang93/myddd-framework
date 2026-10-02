package org.deep361.myddd.mq.listener;

import org.aopalliance.intercept.MethodInterceptor;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.message.InboundMessage;
import org.deep361.myddd.mq.listener.fixture.BadListeners;
import org.deep361.myddd.mq.listener.fixture.MqContexts;
import org.deep361.myddd.mq.listener.fixture.ProbeListener;
import org.deep361.myddd.mq.listener.fixture.ShapeListeners;
import org.deep361.myddd.mq.payload.MessagePayloadConverter;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.support.GenericApplicationContext;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * LS 组：{@code @MessageListener} 扫描的四件事——扫到谁、按什么顺序、什么形态拒绝、什么时候才允许被读。
 *
 * <p>形状与 {@code DomainEventListenerRegistrarTest} 逐条对齐（同一框架里两处注解扫描是同一条约定），
 * 两处刻意的不同只有异常类型（同壳统一 {@link FrameworkException}）与不做 {@code @Domain} 归属校验。
 */
class MessageListenerRegistrarTest {

    @Test
    void should_register_the_annotated_method_with_every_field() {
        MessageListenerRegistrar registrar = MqContexts.registrarOf(MqContexts.refresh(ProbeListener.class));

        assertThat(registrar.listenerCount()).isEqualTo(2);
        RegisteredMessageListener inbound = registrar.listenersFor(ProbeListener.INBOUND_CHANNEL).get(0);
        assertThat(inbound.beanName()).isEqualTo("probeListener");
        assertThat(inbound.channel()).isEqualTo("order-events");
        assertThat(inbound.group()).isEqualTo("g-order");
        assertThat(inbound.description()).isEqualTo("入站消息形态的探针");
        assertThat(inbound.consumesInboundMessage()).isTrue();
        assertThat(inbound.id()).isEqualTo("probeListener#onOrder");

        // 没写 group 的那条：空串是"用适配器缺省组"的标记，参数类型从方法推导出来
        RegisteredMessageListener payload = registrar.listenersFor(ProbeListener.PAYLOAD_CHANNEL).get(0);
        assertThat(payload.consumesInboundMessage()).isFalse();
        assertThat(payload.parameterType()).isEqualTo(ProbeListener.OrderPaid.class);
        assertThat(payload.group()).isEmpty();
    }

    @Test
    void should_ignore_beans_without_listeners() {
        MessageListenerRegistrar registrar = MqContexts.registrarOf(MqContexts.refresh(ShapeListeners.Plain.class));

        assertThat(registrar.listenerCount()).isZero();
        assertThat(registrar.channels()).isEmpty();
    }

    @Test
    void should_pick_the_most_specific_method_when_a_subclass_overrides() {
        RegisteredMessageListener inherited = MqContexts
                .registrarOf(MqContexts.refresh(ShapeListeners.InheritingHook.class))
                .snapshot().get(0);
        assertThat(MqContexts.registrarOf(MqContexts.refresh(ShapeListeners.InheritingHook.class)).listenerCount())
                .isEqualTo(1);
        assertThat(inherited.method().getDeclaringClass()).isEqualTo(ShapeListeners.BaseHook.class);

        RegisteredMessageListener overridden = MqContexts
                .registrarOf(MqContexts.refresh(ShapeListeners.OverridingHook.class))
                .snapshot().get(0);
        assertThat(overridden.method().getDeclaringClass()).isEqualTo(ShapeListeners.OverridingHook.class);
    }

    @Test
    void should_scan_and_invoke_through_a_cglib_proxy_bean() {
        AtomicInteger intercepted = new AtomicInteger();
        ProxyFactory proxyFactory = new ProxyFactory(new ShapeListeners.InterceptedProbe());
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice((MethodInterceptor) invocation -> {
            intercepted.incrementAndGet();
            return invocation.proceed();
        });
        ShapeListeners.InterceptedProbe proxy = ShapeListeners.InterceptedProbe.class.cast(proxyFactory.getProxy());

        GenericApplicationContext context = MqContexts.registrarOnly();
        context.registerBean("interceptedProbe", ShapeListeners.InterceptedProbe.class, () -> proxy);
        context.refresh();

        MessageListenerRegistrar registrar = MqContexts.registrarOf(context);
        assertThat(registrar.listenerCount()).isEqualTo(1);
        new InboundDispatcher(registrar, new MessagePayloadConverter())
                .dispatch(inbound(ShapeListeners.InterceptedProbe.CHANNEL));

        // 注册项带的是"容器实例（代理）+ 声明类方法"，正好是 Spring 自己对 CGLIB 代理的调用方式：切面照常生效
        assertThat(intercepted).hasValue(1);
    }

    @Test
    void should_not_scan_a_bean_whose_type_is_only_an_interface() {
        ProxyFactory proxyFactory = new ProxyFactory(new ShapeListeners.InterfaceOnlyProbe());
        proxyFactory.setInterfaces(ShapeListeners.OrderApi.class);
        ShapeListeners.OrderApi proxy = (ShapeListeners.OrderApi) proxyFactory.getProxy();

        GenericApplicationContext context = MqContexts.registrarOnly();
        context.registerBean("apiProbe", ShapeListeners.OrderApi.class, () -> proxy);
        context.refresh();

        // 公开的能力边界（与 domain 侧同形）：bean 类型只到接口时，实现类上的 @MessageListener 扫不到。
        // Boot 缺省 CGLIB，故默认用法不受影响；不做二次 unwrap 是为了不让"本类→父类→接口"走出两条分叉的路径。
        assertThat(MqContexts.registrarOf(context).listenerCount()).isZero();
    }

    @Test
    void should_reject_a_static_listener() {
        assertSignatureRejected(BadListeners.StaticProbe.class, "staticProbe#on", "必须是实例方法（非 static）");
    }

    @Test
    void should_reject_a_private_listener() {
        assertSignatureRejected(BadListeners.PrivateProbe.class, "privateProbe#on", "不能是 private");
    }

    @Test
    void should_reject_a_non_void_listener() {
        assertSignatureRejected(BadListeners.NonVoidProbe.class, "nonVoidProbe#on", "返回值必须是 void，实际 java.lang.String");
    }

    @Test
    void should_reject_a_no_argument_listener() {
        assertSignatureRejected(BadListeners.NoArgumentProbe.class, "noArgumentProbe#on", "必须恰好声明 1 个参数，实际 0 个");
    }

    @Test
    void should_reject_a_two_argument_listener() {
        assertSignatureRejected(BadListeners.TwoArgumentProbe.class, "twoArgumentProbe#on", "实际 2 个");
    }

    @Test
    void should_reject_a_blank_channel_on_the_annotation() {
        assertSignatureRejected(BadListeners.BlankChannelProbe.class, "blankChannelProbe#on",
                "channel 必填且不能为空白", "myddd.mq.channels.<channel>.provider");
    }

    @Test
    void should_keep_two_listeners_of_the_same_channel_in_a_total_order() {
        MessageListenerRegistrar registrar = MqContexts
                .registrarOf(MqContexts.refresh(ShapeListeners.SameChannelPair.class));

        // 同一 bean、同一通道、同一组：剩下的排序键只有方法名，结果不能依赖反射返回顺序
        assertThat(registrar.listenersFor(ShapeListeners.SameChannelPair.CHANNEL))
                .extracting(RegisteredMessageListener::id)
                .containsExactly("sameChannelPair#onA", "sameChannelPair#onB");
        assertThat(registrar.groupOf(ShapeListeners.SameChannelPair.CHANNEL)).isEqualTo("g-pair");
    }

    @Test
    void should_reject_two_different_groups_on_one_channel() {
        Throwable conflict = catchThrowable(() -> MqContexts
                .refresh(ProbeListener.class, ShapeListeners.ConflictingGroup.class));

        // 一个通道一个进程只能落一个消费者组：两个组＝两个消费者实例＝"谁收到哪一条"没有定义
        assertThat(conflict).isInstanceOf(FrameworkException.class);
        assertThat(conflict.getMessage()).contains("order-events", "probeListener#onOrder", "g-order",
                "conflictingGroup#onConflict", "g-other", "只能属于一个消费者组");
    }

    @Test
    void should_expose_channels_sorted_and_deduplicated() {
        MessageListenerRegistrar registrar = MqContexts.registrarOf(MqContexts
                .refresh(ShapeListeners.SameChannelPair.class, ShapeListeners.SingleChannel.class));

        assertThat(registrar.channels()).containsExactly("pair-channel", "single-channel");
        assertThat(registrar.listenerCount()).isEqualTo(3);
    }

    @Test
    void should_refuse_to_answer_before_the_snapshot_is_frozen() {
        MessageListenerRegistrar registrar = new MessageListenerRegistrar(MqContexts.unrefreshed());

        // 宁可抛"尚未冻结"也不返回空列表：空列表会让适配器以为"这进程没人监听"，从而安静地不订阅
        assertThatThrownBy(registrar::snapshot).isInstanceOf(IllegalStateException.class).hasMessageContaining("尚未冻结");
        assertThatThrownBy(() -> registrar.listenersFor(ProbeListener.INBOUND_CHANNEL))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("尚未冻结");
        assertThatThrownBy(registrar::channels)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("尚未冻结");
        assertThatThrownBy(() -> registrar.groupOf(ProbeListener.INBOUND_CHANNEL))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("尚未冻结");
    }

    @Test
    void should_reject_a_blank_channel_when_looking_up_and_answer_empty_for_an_unknown_one() {
        MessageListenerRegistrar registrar = MqContexts.registrarOf(MqContexts.refresh(ProbeListener.class));

        assertThatThrownBy(() -> registrar.listenersFor(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能为空白");
        assertThatThrownBy(() -> registrar.listenersFor(" "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能为空白");
        // 命中零条不在这里报错：该不该报错属于分发（AB8）与订阅计划（CP3）的职责
        assertThat(registrar.listenersFor("no-such-channel")).isEmpty();
        assertThatThrownBy(() -> registrar.groupOf("no-such-channel"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("order-events")
                .hasMessageContaining("order-paid");
    }

    @Test
    void should_skip_the_scoped_target_bean() {
        GenericApplicationContext context = MqContexts.registrarOnly();
        context.registerBean("scopedTarget.probeListener", ProbeListener.class, ProbeListener::new);
        context.registerBean("probeListener", ProbeListener.class, ProbeListener::new);
        context.refresh();

        // scope 代理与它的目标 bean 同名同方法：两条都注册等于同一条消息投两次
        MessageListenerRegistrar registrar = MqContexts.registrarOf(context);
        assertThat(registrar.listenerCount()).isEqualTo(2);
        assertThat(registrar.snapshot()).extracting(RegisteredMessageListener::beanName).containsOnly("probeListener");
    }

    @Test
    void should_invoke_a_package_private_listener_from_another_package() {
        GenericApplicationContext context = MqContexts.refresh(ShapeListeners.PackagePrivateHook.class);
        MessageListenerRegistrar registrar = MqContexts.registrarOf(context);
        InboundMessage message = inbound(ShapeListeners.PackagePrivateHook.CHANNEL);

        assertThat(registrar.listenerCount()).isEqualTo(1);
        assertThat(new InboundDispatcher(registrar, new MessagePayloadConverter()).dispatch(message)).isEqualTo(1);
        // 钉住 makeAccessible：装配层在另一个包里、方法不公开，照样调得动
        assertThat(context.getBean(ShapeListeners.PackagePrivateHook.class).seen()).containsExactly(message);
    }

    /** 一条非法签名一个内部类，逐个扫：断言"消息里同时有哪个方法与为什么"，不靠反射返回顺序碰运气。 */
    private static void assertSignatureRejected(Class<?> beanClass, String... expectedParts) {
        Throwable rejected = catchThrowable(() -> MqContexts.refresh(beanClass));

        assertThat(rejected).isInstanceOf(FrameworkException.class);
        assertThat(rejected.getMessage()).contains("非法的 @MessageListener 方法").contains(expectedParts);
    }

    private static InboundMessage inbound(String channel) {
        return new InboundMessage(channel, "{}", "m-1", null);
    }
}
