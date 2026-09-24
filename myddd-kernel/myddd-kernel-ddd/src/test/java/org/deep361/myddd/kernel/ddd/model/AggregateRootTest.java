package org.deep361.myddd.kernel.ddd.model;

import org.deep361.myddd.kernel.ddd.event.AbstractDomainEvent;
import org.deep361.myddd.kernel.ddd.event.DomainEvent;
import org.deep361.myddd.kernel.ddd.model.support.ExternalOrder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AggregateRootTest {

    static class OrderPaidEvent extends AbstractDomainEvent {
        private final String orderId;

        OrderPaidEvent(String orderId) {
            this.orderId = orderId;
        }

        String orderId() {
            return orderId;
        }
    }

    static class ShipmentEvent extends AbstractDomainEvent {
        private final String orderId;

        ShipmentEvent(String orderId) {
            this.orderId = orderId;
        }

        String orderId() {
            return orderId;
        }
    }

    static class OrderCancelledEvent extends AbstractDomainEvent {
        private final String orderId;

        OrderCancelledEvent(String orderId) {
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
            registerEvent(new OrderPaidEvent(String.valueOf(getId())));
        }

        void ship() {
            registerEvent(new ShipmentEvent(String.valueOf(getId())));
        }

        void cancel() {
            registerEvent(new OrderCancelledEvent(String.valueOf(getId())));
        }
    }

    // 与 Order 同 id 的普通实体：聚合根若另行覆写判等（放宽为"任意 Entity + 同 id"）即不相等，用于钉住继承语义
    static class PlainOrder extends Entity<Long> {
        PlainOrder(Long id) {
            super(id);
        }
    }

    @Test
    void fresh_aggregate_should_have_no_events() {
        assertThat(new Order(1L).domainEvents()).isEmpty();
    }

    @Test
    void should_collect_registered_events_in_order() {
        Order order = new Order(1L);
        order.pay();
        order.ship();
        order.pay();
        order.cancel(); // 追加区分度事件：pay/ship/pay 本身对称，反转后无法与正序区分

        List<DomainEvent> events = order.domainEvents();

        assertThat(events).hasSize(4);
        assertThat(events.get(0)).isInstanceOf(OrderPaidEvent.class);
        assertThat(events.get(1)).isInstanceOf(ShipmentEvent.class);
        assertThat(events.get(2)).isInstanceOf(OrderPaidEvent.class);
        assertThat(events.get(3)).isInstanceOf(OrderCancelledEvent.class);
        assertThat(((OrderPaidEvent) events.get(0)).orderId()).isEqualTo("1");
        assertThat(((ShipmentEvent) events.get(1)).orderId()).isEqualTo("1");
        assertThat(((OrderPaidEvent) events.get(2)).orderId()).isEqualTo("1");
        assertThat(((OrderCancelledEvent) events.get(3)).orderId()).isEqualTo("1");
    }

    @Test
    void domain_events_view_should_be_immutable() {
        Order order = new Order(1L);
        order.pay();

        List<DomainEvent> events = order.domainEvents();

        assertThatThrownBy(() -> events.add(new OrderPaidEvent("1")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> events.set(0, new OrderPaidEvent("1")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> events.remove(0))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void domain_events_should_be_snapshot_at_call_time() {
        Order order = new Order(1L);
        order.pay();

        List<DomainEvent> snapshot = order.domainEvents();
        order.pay();

        assertThat(snapshot).hasSize(1); // 返回值为调用时刻的快照，不随之后的登记而增长
        assertThat(order.domainEvents()).hasSize(2);
    }

    @Test
    void domain_events_should_be_fresh_per_call() {
        Order order = new Order(1L);
        order.pay();

        List<DomainEvent> first = order.domainEvents();
        List<DomainEvent> second = order.domainEvents();
        order.ship();

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(first).isNotSameAs(second); // 非空结果每次调用生成新快照对象，不缓存返回结果
        assertThat(order.domainEvents()).hasSize(2);
    }

    @Test
    void clear_should_empty_events() {
        Order order = new Order(1L);
        order.pay();
        order.clearDomainEvents();

        assertThat(order.domainEvents()).isEmpty();
    }

    @Test
    void clear_should_leave_aggregate_usable() {
        Order order = new Order(1L);
        order.pay();
        order.clearDomainEvents();

        order.ship();

        assertThat(order.domainEvents()).hasSize(1);
        assertThat(order.domainEvents().get(0)).isInstanceOf(ShipmentEvent.class);
    }

    @Test
    void clear_on_empty_aggregate_should_not_throw() {
        Order order = new Order(1L);

        order.clearDomainEvents();
        order.clearDomainEvents();

        assertThat(order.domainEvents()).isEmpty();
    }

    @Test
    void should_reject_null_event() {
        Order order = new Order(1L);

        assertThatThrownBy(() -> order.registerEvent(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("领域事件不能为 null");
    }

    @Test
    void register_event_should_be_protected_not_public() {
        Method method = findRegisterEvent(AggregateRoot.class);

        assertThat(Modifier.isProtected(method.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(method.getModifiers())).isFalse();
    }

    private static Method findRegisterEvent(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod("registerEvent", DomainEvent.class);
            } catch (NoSuchMethodException ignored) {
                // 方法若上移到父类仍满足契约，沿继承链继续查找
            }
        }
        throw new AssertionError("未在 " + type + " 及其父类中找到 registerEvent(DomainEvent)");
    }

    @Test
    void cross_package_subclass_should_be_able_to_register_events() {
        ExternalOrder order = new ExternalOrder(1L);

        order.pay();

        assertThat(order.domainEvents()).hasSize(1);
        assertThat(order.domainEvents().get(0)).isInstanceOf(ExternalOrder.ExternalOrderPaidEvent.class);
    }

    @Test
    void should_keep_duplicate_registrations_of_the_same_event_instance() {
        Order order = new Order(1L);
        OrderPaidEvent event = new OrderPaidEvent("1");

        order.registerEvent(event);
        order.registerEvent(event);

        assertThat(order.domainEvents()).hasSize(2);
        assertThat(order.domainEvents().get(0)).isSameAs(event);
        assertThat(order.domainEvents().get(1)).isSameAs(event);
    }

    @Test
    void should_keep_entity_identity_semantics() {
        assertThat(new Order(1L)).isEqualTo(new Order(1L));
        assertThat(new Order(1L)).isNotEqualTo(new Order(2L));
    }

    @Test
    void aggregate_root_should_not_equal_plain_entity_with_same_id() {
        assertThat((Entity<Long>) new Order(1L)).isNotEqualTo(new PlainOrder(1L));
        assertThat((Entity<Long>) new PlainOrder(1L)).isNotEqualTo(new Order(1L));
    }

    @Test
    void should_expose_id() {
        assertThat(new Order(9L).getId()).isEqualTo(9L);
    }
}
