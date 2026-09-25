package org.deep361.myddd.domain.event;

import org.deep361.myddd.domain.event.fixture.EventFixtures;
import org.deep361.myddd.domain.event.fixture.InvalidListeners;
import org.deep361.myddd.domain.orphan.OrphanListenerBean;
import org.deep361.myddd.kernel.ddd.event.DomainEvent;
import org.deep361.myddd.kernel.ddd.event.DomainEventStage;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainEventListenerRegistrarTest {

    @Test
    void should_register_annotated_listener_method() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("paidListenerBean", new EventFixtures.PaidListenerBean(new EventFixtures.EventRecorder()));

        List<RegisteredListener> listeners = EventTestSupport.freeze(beanFactory).listeners();

        assertThat(listeners).hasSize(1);
        RegisteredListener listener = listeners.get(0);
        assertThat(listener.beanName()).isEqualTo("paidListenerBean");
        assertThat(listener.stage()).isEqualTo(DomainEventStage.IN_TRANSACTION);
        assertThat(listener.async()).isFalse();
        assertThat(listener.order()).isZero();
        assertThat(listener.describe()).isEqualTo("paidListenerBean#on");
    }

    @Test
    void should_default_to_after_commit_stage_when_unspecified() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("defaultStageListenerBean",
                new EventFixtures.DefaultStageListenerBean(new EventFixtures.EventRecorder()));

        RegisteredListener listener = EventTestSupport.freeze(beanFactory).listeners().get(0);

        assertThat(listener.stage()).isEqualTo(DomainEventStage.AFTER_COMMIT);
        assertThat(listener.async()).isFalse();
    }

    @Test
    void should_sort_by_stage_then_order_then_bean_name() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("lowOrderListenerBean", new EventFixtures.LowOrderListenerBean(recorder));
        beanFactory.addBean("defaultStageListenerBean", new EventFixtures.DefaultStageListenerBean(recorder));
        beanFactory.addBean("paidListenerBean", new EventFixtures.PaidListenerBean(recorder));
        beanFactory.addBean("asyncListenerBean", new EventFixtures.AsyncListenerBean(recorder));
        beanFactory.addBean("highOrderListenerBean", new EventFixtures.HighOrderListenerBean(recorder));

        List<RegisteredListener> listeners = EventTestSupport.freeze(beanFactory).listeners();

        assertThat(listeners).extracting(RegisteredListener::describe).containsExactly(
                "highOrderListenerBean#on",
                "paidListenerBean#on",
                "lowOrderListenerBean#on",
                "defaultStageListenerBean#on",
                "asyncListenerBean#on");
    }

    @Test
    void should_break_order_ties_by_method_name_then_signature() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("nameOrderListenerBean",
                new EventFixtures.NameOrderListenerBean(new EventFixtures.EventRecorder()));

        List<RegisteredListener> listeners = EventTestSupport.freeze(beanFactory).listeners();

        // 声明顺序是 zeta → on(DomainEvent) → on(OrderPaidEvent) → alpha，期望顺序必须只由排序键决定
        assertThat(listeners)
                .extracting(listener -> listener.method().getName()
                        + "(" + listener.method().getParameterTypes()[0].getSimpleName() + ")")
                .containsExactly("alpha(OrderPaidEvent)", "on(OrderPaidEvent)", "on(DomainEvent)", "zeta(OrderPaidEvent)");
    }

    @Test
    void should_freeze_into_unmodifiable_list() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("paidListenerBean", new EventFixtures.PaidListenerBean(new EventFixtures.EventRecorder()));

        DomainEventListenerRegistrar registrar = EventTestSupport.freeze(beanFactory);

        assertThat(registrar.listeners()).hasSize(1);
        assertThatThrownBy(() -> registrar.listeners().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_fail_when_listeners_requested_before_freeze() {
        DomainEventListenerRegistrar registrar = new DomainEventListenerRegistrar(
                new StaticListableBeanFactory(), EventTestSupport.fixtureModules());

        assertThatThrownBy(registrar::listeners)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未冻结");
    }

    @Test
    void should_skip_beans_without_listener_methods() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("plainBean", new EventFixtures.PlainBean());
        beanFactory.addBean("paidListenerBean", new EventFixtures.PaidListenerBean(recorder));

        assertThat(EventTestSupport.freeze(beanFactory).listeners())
                .extracting(RegisteredListener::beanName)
                .containsExactly("paidListenerBean");
    }

    @Test
    void should_skip_scoped_target_bean_and_keep_the_proxy_registered() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("scopedTarget.paidListenerBean", new EventFixtures.PaidListenerBean(recorder));
        beanFactory.addBean("paidListenerBean", new EventFixtures.PaidListenerBean(recorder));

        // scope 代理的目标 bean 与代理同名同方法：只注册代理，否则同一监听器会被投递两次
        assertThat(EventTestSupport.freeze(beanFactory).listeners())
                .extracting(RegisteredListener::beanName)
                .containsExactly("paidListenerBean");
    }

    @Test
    void should_register_jdk_proxied_listener_as_module_owned() throws Throwable {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        ProxyFactory proxyFactory = new ProxyFactory(new EventFixtures.ContractListenerBean(recorder));
        proxyFactory.setInterfaces(EventFixtures.ContractListener.class);
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("proxiedContractListenerBean", proxyFactory.getProxy());

        List<RegisteredListener> listeners = EventTestSupport.freeze(beanFactory).listeners();

        // 归属按目标类判定（JDK 代理类不在扫描包内），调用走代理实例，由代理转发给目标
        assertThat(listeners).hasSize(1);
        listeners.get(0).invoke(paidEvent());
        assertThat(recorder.names()).containsExactly("contract");
    }

    @Test
    void should_register_interface_annotated_method() throws Throwable {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("contractListenerBean", new EventFixtures.ContractListenerBean(recorder));

        List<RegisteredListener> listeners = EventTestSupport.freeze(beanFactory).listeners();

        assertThat(listeners).hasSize(1);
        listeners.get(0).invoke(paidEvent());
        assertThat(recorder.names()).containsExactly("contract");
    }

    @Test
    void should_register_inherited_annotated_method_once() throws Throwable {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("inheritingListenerBean", new EventFixtures.InheritingListenerBean(recorder));

        List<RegisteredListener> listeners = EventTestSupport.freeze(beanFactory).listeners();

        assertThat(listeners).hasSize(1);
        listeners.get(0).invoke(paidEvent());
        assertThat(recorder.names()).containsExactly("base");
    }

    @Test
    void should_invoke_most_specific_override() throws Throwable {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("overridingListenerBean", new EventFixtures.OverridingListenerBean(recorder));

        List<RegisteredListener> listeners = EventTestSupport.freeze(beanFactory).listeners();

        assertThat(listeners).hasSize(1);
        listeners.get(0).invoke(paidEvent());
        assertThat(recorder.names()).containsExactly("overridden");
    }

    @Test
    void should_fail_when_listener_bean_is_outside_modules() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("orphanListenerBean", new OrphanListenerBean());

        assertThatThrownBy(() -> EventTestSupport.freeze(beanFactory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orphanListenerBean")
                .hasMessageContaining("不属于任何 @Domain 领域模块");
    }

    @Test
    void should_reject_static_listener_method() {
        assertInvalidListener(new InvalidListeners.StaticListenerBean(), "非 static");
    }

    @Test
    void should_reject_non_void_listener_method() {
        assertInvalidListener(new InvalidListeners.NonVoidListenerBean(), "返回值必须是 void");
    }

    @Test
    void should_reject_listener_method_with_invalid_parameter_count() {
        assertInvalidListener(new InvalidListeners.NoParameterListenerBean(), "恰好声明 1 个参数，实际 0 个");
        assertInvalidListener(new InvalidListeners.TwoParameterListenerBean(), "恰好声明 1 个参数，实际 2 个");
    }

    @Test
    void should_reject_listener_method_with_non_domain_event_parameter() {
        assertInvalidListener(new InvalidListeners.WrongParameterTypeListenerBean(), DomainEvent.class.getName());
    }

    @Test
    void should_reject_private_listener_method() {
        assertInvalidListener(new InvalidListeners.PrivateListenerBean(), "不能是 private");
    }

    @Test
    void should_reject_async_listener_in_transaction() {
        assertInvalidListener(new InvalidListeners.AsyncInTransactionListenerBean(), "async=true");
    }

    private static void assertInvalidListener(Object bean, String expectedMessagePart) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("invalidListenerBean", bean);

        assertThatThrownBy(() -> EventTestSupport.freeze(beanFactory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法的 @DomainEventListener 方法")
                .hasMessageContaining(expectedMessagePart);
    }

    private static EventFixtures.OrderPaidEvent paidEvent() {
        return new EventFixtures.OrderPaidEvent("e-1", Instant.parse("2026-09-25T00:00:00Z"), "o-1");
    }
}
