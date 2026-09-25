package org.deep361.myddd.kernel.ddd.model;

import org.deep361.myddd.kernel.ddd.event.AbstractDomainEvent;
import org.deep361.myddd.kernel.ddd.event.DomainEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AggregateRootDrainEventsTest {

    static class PaidEvent extends AbstractDomainEvent {
        private final String orderId;

        PaidEvent(String orderId) {
            this.orderId = orderId;
        }

        String orderId() {
            return orderId;
        }
    }

    static class ShippedEvent extends AbstractDomainEvent {
        private final String orderId;

        ShippedEvent(String orderId) {
            this.orderId = orderId;
        }

        String orderId() {
            return orderId;
        }
    }

    static class Order extends AggregateRoot<Long> {

        Order(Long id) {
            super(id);
        }

        void pay() {
            registerEvent(new PaidEvent(String.valueOf(getId())));
        }

        void ship() {
            registerEvent(new ShippedEvent(String.valueOf(getId())));
        }

        /** 公开登记入口：模拟"监听器在投递期间向同一聚合追加事件"。 */
        void record(DomainEvent event) {
            registerEvent(event);
        }
    }

    @Test
    void drain_on_fresh_aggregate_should_return_empty_list() {
        assertThat(new Order(1L).drainEvents()).isEmpty();
    }

    @Test
    void drain_should_return_events_in_registration_order() {
        Order order = new Order(1L);
        order.pay();
        order.ship();
        order.pay();

        List<DomainEvent> drained = order.drainEvents();

        assertThat(drained).hasSize(3);
        assertThat(drained.get(0)).isInstanceOf(PaidEvent.class);
        assertThat(drained.get(1)).isInstanceOf(ShippedEvent.class);
        assertThat(drained.get(2)).isInstanceOf(PaidEvent.class);
        assertThat(((PaidEvent) drained.get(0)).orderId()).isEqualTo("1");
    }

    @Test
    void drain_should_clear_the_aggregate() {
        Order order = new Order(1L);
        order.pay();

        order.drainEvents();

        assertThat(order.domainEvents()).isEmpty();
    }

    @Test
    void drained_snapshot_should_survive_the_following_drain() {
        Order order = new Order(1L);
        order.pay();

        List<DomainEvent> first = order.drainEvents();
        List<DomainEvent> second = order.drainEvents();

        assertThat(first).hasSize(1); // 已取出的快照不随聚合清空而被清掉
        assertThat(second).isEmpty();
        assertThat(first).isNotSameAs(second);
    }

    @Test
    void drained_list_should_be_immutable() {
        Order order = new Order(1L);
        order.pay();

        List<DomainEvent> drained = order.drainEvents();

        assertThatThrownBy(() -> drained.add(new PaidEvent("1")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(drained::clear)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void drain_should_leave_aggregate_usable() {
        Order order = new Order(1L);
        order.pay();
        order.drainEvents();

        order.ship();

        assertThat(order.domainEvents()).hasSize(1);
        assertThat(order.domainEvents().get(0)).isInstanceOf(ShippedEvent.class);
    }

    @Test
    void drain_should_not_drop_events_registered_after_it() {
        Order order = new Order(1L);
        order.pay();
        order.drainEvents();
        order.record(new ShippedEvent("1"));
        order.record(new PaidEvent("1"));

        assertThat(order.drainEvents()).hasSize(2);
    }

    @Test
    void drain_should_preserve_registration_order_when_sequence_is_not_palindromic() {
        Order order = new Order(1L);
        order.pay();
        order.ship();

        List<DomainEvent> drained = order.drainEvents();

        assertThat(drained).hasSize(2);
        assertThat(drained.get(0)).isInstanceOf(PaidEvent.class);
        assertThat(drained.get(1)).isInstanceOf(ShippedEvent.class);
    }
}
