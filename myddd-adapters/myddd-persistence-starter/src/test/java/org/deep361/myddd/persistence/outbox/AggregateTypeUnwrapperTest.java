package org.deep361.myddd.persistence.outbox;

import org.aopalliance.intercept.MethodInterceptor;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.ddd.event.AbstractDomainEvent;
import org.deep361.myddd.kernel.ddd.event.DomainEvent;
import org.deep361.myddd.kernel.ddd.event.DomainEventPublisher;
import org.deep361.myddd.kernel.ddd.event.DomainEventSource;
import org.deep361.myddd.kernel.ddd.model.AggregateRoot;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 装饰器的两条职责：三参原语原样转发；聚合级发布改成"用真实实例的类名与 id"逐事件转发。
 *
 * <p>这里的被装饰者用捕获型替身，而不是 domain-starter 的真实发布器：本类要钉的是"转发出去的那三个参数是
 * 什么、按什么顺序、几条"，真实发布器那侧的行为（Outbox 记录、监听器投递）已由 Plan 2 Task 16/17 钉过，
 * 装配接线见 {@link AggregateTypeUnwrapperBeanPostProcessorTest}。
 */
class AggregateTypeUnwrapperTest {

    /** 记录每次三参转发的现场，格式 {@code aggregateType|aggregateId|eventType}。 */
    static final class Capturing implements DomainEventPublisher {

        final List<String> calls = new ArrayList<>();
        RuntimeException failOn;

        @Override
        public void publish(DomainEvent event, String aggregateType, String aggregateId) {
            calls.add(aggregateType + "|" + aggregateId + "|" + event.getClass().getSimpleName());
            if (failOn != null) {
                throw failOn;
            }
        }
    }
    static class OrderCreated extends AbstractDomainEvent {
    }

    static class OrderShipped extends AbstractDomainEvent {
    }

    static class Order extends AggregateRoot<Long> {

        Order(Long id) {
            super(id);
        }

        void fireTwo() {
            registerEvent(new OrderCreated());
            registerEvent(new OrderShipped());
        }
    }

    /**
     * 非聚合根的裸来源：id 直接给 null，用来钉"null id 必须快速失败"（W7）。
     *
     * <p>{@code drainEvents()} 与真实聚合一样"取一次就空"：如果它每次都返回新事件，那么 null-id 守卫一旦被删，
     * 外层 {@code while (!batch.isEmpty())} 会变成无限循环并撑爆 surefire 的 fork（实测 88 条之后
     * {@code OutOfMemoryError: Java heap space}）——那会把"这个变异被 W7 抓到"的证据换成一场堆栈噪声。
     */
    static final class NullIdSource implements DomainEventSource<Long> {

        private final List<DomainEvent> pending = new ArrayList<>(List.of(new OrderCreated()));

        @Override
        public Long getId() {
            return null;
        }

        @Override
        public List<DomainEvent> drainEvents() {
            List<DomainEvent> snapshot = List.copyOf(pending);
            pending.clear();
            return snapshot;
        }
    }

    /** 第一次 drain 时补登记一条事件的来源，钉"再取一批直到为空"（W4）。 */
    static final class ReRegisteringSource implements DomainEventSource<Long> {

        private final List<DomainEvent> pending = new ArrayList<>(List.of(new OrderCreated(), new OrderShipped()));
        private boolean refilled;

        @Override
        public Long getId() {
            return 9L;
        }

        @Override
        public List<DomainEvent> drainEvents() {
            List<DomainEvent> snapshot = List.copyOf(pending);
            pending.clear();
            if (!refilled) {
                refilled = true;
                pending.add(new OrderShipped());
            }
            return snapshot;
        }
    }

    private static Object cglib(Object target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice((MethodInterceptor) invocation -> invocation.proceed());
        return factory.getProxy();
    }
    @Test
    void should_forward_the_three_argument_primitive_to_the_delegate_unchanged() {
        Capturing delegate = new Capturing();
        DomainEventPublisher unwrapper = new AggregateTypeUnwrapper(delegate);
        OrderCreated event = new OrderCreated();

        unwrapper.publish(event, "com.example.Anything", "42");

        assertThat(delegate.calls).containsExactly("com.example.Anything|42|OrderCreated");
    }

    @Test
    void should_publish_every_drained_event_in_registration_order() {
        Capturing delegate = new Capturing();
        Order order = new Order(42L);
        order.fireTwo();

        new AggregateTypeUnwrapper(delegate).publish(order);

        assertThat(delegate.calls).containsExactly(
                Order.class.getName() + "|42|OrderCreated",
                Order.class.getName() + "|42|OrderShipped");
    }

    @Test
    void should_use_the_unwrapped_class_name_and_the_real_id_for_a_proxy_aggregate() {
        Capturing delegate = new Capturing();
        Order target = new Order(42L);
        target.fireTwo();
        Object proxy = cglib(target);

        new AggregateTypeUnwrapper(delegate).publish((DomainEventSource<?>) proxy);

        // 未装饰时这里是 "…Order$$SpringCGLIB$$0|null|…"（Plan 3 头部 R1 的实测现象）。
        assertThat(delegate.calls).containsExactly(
                Order.class.getName() + "|42|OrderCreated",
                Order.class.getName() + "|42|OrderShipped");
        assertThat(proxy.getClass().getName()).isNotEqualTo(Order.class.getName());
    }

    @Test
    void should_keep_draining_until_the_source_runs_empty() {
        Capturing delegate = new Capturing();

        new AggregateTypeUnwrapper(delegate).publish(new ReRegisteringSource());

        // 第一批 2 条 + 补登记的 1 条：与 kernel 默认实现的"再取一批"语义逐条对齐。
        assertThat(delegate.calls).containsExactly(
                ReRegisteringSource.class.getName() + "|9|OrderCreated",
                ReRegisteringSource.class.getName() + "|9|OrderShipped",
                ReRegisteringSource.class.getName() + "|9|OrderShipped");
    }
    @Test
    void should_let_a_delegate_failure_propagate_without_re_registering_events() {
        Capturing delegate = new Capturing();
        delegate.failOn = new IllegalStateException("投递炸了");
        Order order = new Order(42L);
        order.fireTwo();

        assertThatThrownBy(() -> new AggregateTypeUnwrapper(delegate).publish(order))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("投递炸了");

        // kernel 的失败语义：已 drain 出的事件不回滚、不重放（"不丢"由同事务 Outbox 记录负责）。
        assertThat(delegate.calls).hasSize(1);
        assertThat(order.drainEvents()).isEmpty();
    }

    @Test
    void should_reject_a_null_source_with_the_kernel_message() {
        assertThatThrownBy(() -> new AggregateTypeUnwrapper(new Capturing()).publish(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("聚合根不能为 null");
    }

    @Test
    void should_fail_fast_when_the_aggregate_reports_a_null_id() {
        Capturing delegate = new Capturing();

        // Entity 契约是"id 创建时确定且不可为 null"，所以 null id 只可能来自拦不到 final getId() 的代理实例。
        // MyBatis 的 javassist 懒加载代理正落在这里：它不实现 Advised，target(...) 拿不回真实聚合，于是类型名
        // 虽能被类名层解包救回（U4），id 仍是 null——本用例钉的就是"这种半成品修复必须整体失败，不许写半个坐标"。
        assertThatThrownBy(() -> new AggregateTypeUnwrapper(delegate).publish(new NullIdSource()))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("id 为 null")
                .hasMessageContaining(NullIdSource.class.getName());

        assertThat(delegate.calls).isEmpty();
    }

    @Test
    void should_produce_exactly_the_kernel_default_coordinates_for_a_plain_aggregate() {
        Order viaUnwrapper = new Order(42L);
        viaUnwrapper.fireTwo();
        Order viaKernel = new Order(42L);
        viaKernel.fireTwo();

        Capturing unwrapperDelegate = new Capturing();
        new AggregateTypeUnwrapper(unwrapperDelegate).publish(viaUnwrapper);
        Capturing kernelDelegate = new Capturing();
        kernelDelegate.publish(viaKernel);   // 接口默认实现：getClass().getName()

        assertThat(unwrapperDelegate.calls).isEqualTo(kernelDelegate.calls);
        assertThat(unwrapperDelegate.calls).hasSize(2);
    }

    @Test
    void should_publish_nothing_for_an_aggregate_without_events() {
        Capturing delegate = new Capturing();

        assertThatCode(() -> new AggregateTypeUnwrapper(delegate).publish(new Order(42L)))
                .doesNotThrowAnyException();

        assertThat(delegate.calls).isEmpty();
    }
}
