package org.deep361.myddd.kernel.ddd.event;

import org.deep361.myddd.kernel.ddd.model.AggregateRoot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainEventPublisherTest {

    static class PaidEvent extends AbstractDomainEvent {
    }

    static class ShippedEvent extends AbstractDomainEvent {
    }

    static class Order extends AggregateRoot<Long> {

        Order(Long id) {
            super(id);
        }

        void pay() {
            registerEvent(new PaidEvent());
        }

        void ship() {
            registerEvent(new ShippedEvent());
        }

        void record(DomainEvent event) {
            registerEvent(event);
        }
    }

    /** 记录型发布器：只实现 primitive 方法，聚合级语义全部走接口默认实现。 */
    static class RecordingPublisher implements DomainEventPublisher {

        final List<DomainEvent> events = new ArrayList<>();
        final List<String> coordinates = new ArrayList<>();
        private Order reactWith;
        private DomainEvent reaction;

        /** 配置"收到第一个事件后向该聚合再登记一个事件"，用于钉住默认实现的循环语义。 */
        void reactOnFirstEvent(Order aggregate, DomainEvent event) {
            this.reactWith = aggregate;
            this.reaction = event;
        }

        @Override
        public void publish(DomainEvent event, String aggregateType, String aggregateId) {
            events.add(event);
            coordinates.add(aggregateType + "#" + aggregateId);
            Order target = reactWith;
            DomainEvent next = reaction;
            reactWith = null; // 只反应一次，否则默认实现的循环不会终止
            reaction = null;
            if (target != null) {
                target.record(next);
            }
        }
    }

    @Test
    void should_reject_null_aggregate() {
        RecordingPublisher publisher = new RecordingPublisher();

        assertThatThrownBy(() -> publisher.publish((AggregateRoot<?>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("聚合根不能为 null");
    }

    @Test
    void should_publish_each_collected_event_with_aggregate_coordinates() {
        Order order = new Order(7L);
        order.pay();
        order.ship();
        RecordingPublisher publisher = new RecordingPublisher();

        publisher.publish(order);

        assertThat(publisher.events).hasSize(2);
        assertThat(publisher.events.get(0)).isInstanceOf(PaidEvent.class);
        assertThat(publisher.events.get(1)).isInstanceOf(ShippedEvent.class);
        assertThat(publisher.coordinates).containsExactly(
                Order.class.getName() + "#7",
                Order.class.getName() + "#7");
    }

    @Test
    void should_drain_aggregate_after_publishing() {
        Order order = new Order(7L);
        order.pay();

        new RecordingPublisher().publish(order);

        assertThat(order.domainEvents()).isEmpty();
    }

    @Test
    void should_not_touch_aggregate_without_events() {
        Order order = new Order(7L);
        RecordingPublisher publisher = new RecordingPublisher();

        publisher.publish(order);

        assertThat(publisher.events).isEmpty();
        assertThat(order.domainEvents()).isEmpty();
    }

    @Test
    void should_keep_publishing_events_registered_during_publishing() {
        Order order = new Order(7L);
        order.pay();
        RecordingPublisher publisher = new RecordingPublisher();
        publisher.reactOnFirstEvent(order, new ShippedEvent());

        publisher.publish(order);

        assertThat(publisher.events).hasSize(2);
        assertThat(publisher.events.get(0)).isInstanceOf(PaidEvent.class);
        assertThat(publisher.events.get(1)).isInstanceOf(ShippedEvent.class);
        assertThat(order.domainEvents()).isEmpty();
    }

    @Test
    void primitive_publish_should_be_the_only_abstract_method() throws Exception {
        Method primitive = DomainEventPublisher.class.getMethod(
                "publish", DomainEvent.class, String.class, String.class);
        Method aggregateLevel = DomainEventPublisher.class.getMethod("publish", AggregateRoot.class);

        assertThat(Modifier.isAbstract(primitive.getModifiers())).isTrue();
        assertThat(primitive.getReturnType()).isEqualTo(void.class);
        assertThat(aggregateLevel.isDefault()).isTrue(); // 适配器只需实现 primitive
    }
}
