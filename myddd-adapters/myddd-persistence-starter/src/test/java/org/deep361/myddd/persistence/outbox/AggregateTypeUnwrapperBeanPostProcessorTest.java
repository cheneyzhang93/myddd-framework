package org.deep361.myddd.persistence.outbox;

import org.aopalliance.intercept.MethodInterceptor;
import org.deep361.myddd.kernel.ddd.event.AbstractDomainEvent;
import org.deep361.myddd.kernel.ddd.event.DomainEvent;
import org.deep361.myddd.kernel.ddd.event.DomainEventPublisher;
import org.deep361.myddd.kernel.ddd.event.DomainEventSource;
import org.deep361.myddd.kernel.ddd.model.AggregateRoot;
import org.deep361.myddd.persistence.autoconfigure.MydddPersistenceAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 装配面的四件事：发布器 bean 被装饰（G1）、装饰后仍按接口取到唯一一个 bean（G2）、装饰器本身在容器里且
 * 同一个发布器只装饰一次（G3）、非发布器 bean 不动（G4），外加一条反面记录：按具体类查已装饰的发布器不再命中（G5）。
 *
 * <p><b>为什么不复用 domain-starter 的真实发布器</b>：{@code SpringDomainEventPublisher} 的构造需要
 * {@code DomainEventDispatcher}（进而需要监听器注册器与线程池），而 {@code MydddDomainEventAutoConfiguration}
 * 又要求 {@code DomainModuleRegistry}——在一个 slice 测试里拉起整棵事件树，钉的就不是"接线对不对"而是
 * domain-starter 能不能跑。本模块从 Task 16 起确实依赖 {@code myddd-domain-starter}（DDL 约定要反射 {@code OutboxRecord}），
 * 但那是"契约类型"的依赖，不构成"在 slice 测试里装配整棵事件树"的理由：G 组要钉的是接线是否发生，不是事件总线本身。
 * 替身 bean 与被装饰者的形状由一手实测对齐：已交付的 bean 方法签名就是
 * {@code public DomainEventPublisher domainEventPublisher(...)}（{@code MydddDomainEventAutoConfiguration.java:87}），
 * 声明类型是接口，因此装饰后按接口注入仍然命中——G2 钉的正是这件事。
 *
 * <p><b>本文件里所有容器行为都经过探针实测</b>（{@code AnnotationConfigApplicationContext} + 同名包装 BPP）：
 * {@code getBean(DomainEventPublisher)} 返回包装类、按原具体类查得到 {@code NoSuchBeanDefinitionException}
 * （消息 {@code No qualifying bean of type '…$SamplePublisher' available}）、非发布器 bean 原样返回、
 * BPP 对同一个 bean 再调一次返回同一实例（计数器停在 1）。
 */
class AggregateTypeUnwrapperBeanPostProcessorTest {

    private static final String PUBLISHER_BEAN_NAME = "domainEventPublisher";

    static class OrderCreated extends AbstractDomainEvent {
    }

    static class Order extends AggregateRoot<Long> {

        Order(Long id) {
            super(id);
        }

        void fire() {
            registerEvent(new OrderCreated());
        }
    }

    /** 与被装饰者同形状：只记录三参转发，不碰监听器。 */
    static class RecordingPublisher implements DomainEventPublisher {

        final List<String> calls = new ArrayList<>();

        @Override
        public void publish(DomainEvent event, String aggregateType, String aggregateId) {
            calls.add(aggregateType + "|" + aggregateId + "|" + event.getClass().getSimpleName());
        }
    }

    /** 与发布器无关的 bean，用来钉"BPP 的判定不是无差别包装"。 */
    static class NotAPublisher {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddPersistenceAutoConfiguration.class));

    private static Object cglib(Object target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice((MethodInterceptor) invocation -> invocation.proceed());
        return factory.getProxy();
    }
    @Test
    void should_decorate_the_publisher_so_a_proxied_aggregate_publishes_real_coordinates() {
        RecordingPublisher original = new RecordingPublisher();
        this.runner.withBean(PUBLISHER_BEAN_NAME, RecordingPublisher.class, () -> original)
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    // 端到端：经容器取到的那个 bean 发布一个被 CGLIB 代理的聚合。
                    Order target = new Order(42L);
                    target.fire();
                    context.getBean(DomainEventPublisher.class).publish((DomainEventSource<?>) cglib(target));

                    // 要点 1+2：类型是聚合本尊、id 是真实 id——代理类名与 null 都不许出现。
                    assertThat(original.calls).containsExactly(Order.class.getName() + "|42|OrderCreated");
                });
    }

    @Test
    void should_keep_the_publisher_reachable_by_its_interface_under_one_bean_name() {
        this.runner.withBean(PUBLISHER_BEAN_NAME, RecordingPublisher.class, RecordingPublisher::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(DomainEventPublisher.class);
                    // bean 名不变：按名注入（@Qualifier("domainEventPublisher")）与日志里的 bean 名都对得上。
                    assertThat(context.getBeanNamesForType(DomainEventPublisher.class))
                            .containsExactly(PUBLISHER_BEAN_NAME);
                    assertThat(context.getBean(DomainEventPublisher.class))
                            .isInstanceOf(AggregateTypeUnwrapper.class);
                });
    }

    @Test
    void should_be_registered_and_wrap_a_publisher_only_once() {
        this.runner.withBean(PUBLISHER_BEAN_NAME, RecordingPublisher.class, RecordingPublisher::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(AggregateTypeUnwrapperBeanPostProcessor.class);
                    AggregateTypeUnwrapperBeanPostProcessor processor =
                            context.getBean(AggregateTypeUnwrapperBeanPostProcessor.class);

                    RecordingPublisher plain = new RecordingPublisher();
                    Object decorated = processor.postProcessAfterInitialization(plain, "anyName");
                    assertThat(decorated).isInstanceOf(AggregateTypeUnwrapper.class);

                    // 已经是装饰器的 bean 不再包第二层（实测：对同一实例再调一次，返回的是同一个对象）。
                    // 业务完全可能自己 new 一个装饰器注册进容器，双层装饰会让"坐标是谁修正的"变成两层问题。
                    assertThat(processor.postProcessAfterInitialization(decorated, "anyName")).isSameAs(decorated);
                });
    }

    @Test
    void should_not_touch_beans_that_are_not_event_publishers() {
        NotAPublisher fixture = new NotAPublisher();
        this.runner.withBean(PUBLISHER_BEAN_NAME, RecordingPublisher.class, RecordingPublisher::new)
                .withBean("plainBean", NotAPublisher.class, () -> fixture)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(NotAPublisher.class)).isSameAs(fixture);
                });
    }

    @Test
    void should_record_that_concrete_class_lookup_of_a_decorated_publisher_no_longer_resolves() {
        RecordingPublisher original = new RecordingPublisher();
        this.runner.withBean(PUBLISHER_BEAN_NAME, RecordingPublisher.class, () -> original)
                .run(context -> {
                    // 包装型 BPP 的固有后果（要点 8）：暴露出去的 singleton 是装饰器，按原具体类查不到。
                    // 已交付实现是 @Bean 方法产物、无构造器注入依赖，业务侧没有理由按具体类取它；
                    // 真按具体类取了，这里给出的是确定的启动期失败，而不是"有时拿到代理有时拿到本尊"。
                    assertThatThrownBy(() -> context.getBean(RecordingPublisher.class))
                            .isInstanceOf(NoSuchBeanDefinitionException.class)
                            .hasMessageContaining("No qualifying bean of type");

                    // 反面：接口注入拿到的装饰器确实把调用转到了那个查不到的原实例上。
                    Order target = new Order(7L);
                    target.fire();
                    context.getBean(DomainEventPublisher.class).publish((DomainEventSource<?>) cglib(target));
                    assertThat(original.calls).containsExactly(Order.class.getName() + "|7|OrderCreated");
                });
    }
}
