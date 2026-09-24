package org.deep361.myddd.kernel.ddd.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityTest {

    static class Order extends Entity<Long> {
        Order(Long id) {
            super(id);
        }
    }

    static class Product extends Entity<Long> {
        Product(Long id) {
            super(id);
        }
    }

    static class SubOrder extends Order {
        SubOrder(Long id) {
            super(id);
        }
    }

    @Test
    void entities_with_same_type_and_id_should_be_equal() {
        assertThat(new Order(1L)).isEqualTo(new Order(1L));
        assertThat(new Order(1000L)).isEqualTo(new Order(1000L)); // 1000L 超出 Long 缓存（-128..127），两个 Long 是不同引用
    }

    @Test
    void entities_with_same_type_and_id_should_share_hash_code() {
        assertThat(new Order(1L)).hasSameHashCodeAs(new Order(1L));
    }

    @Test
    void entities_with_different_id_should_not_be_equal() {
        assertThat(new Order(1L)).isNotEqualTo(new Order(2L));
        assertThat(new Order(1L)).doesNotHaveSameHashCodeAs(new Order(2L));
        assertThat(new Order(0L)).isNotEqualTo(new Order(4294967297L)); // 0L 与 4294967297L 的 Long.hashCode 相同（均为 0）
    }

    @Test
    void entities_of_different_types_with_same_id_should_not_be_equal() {
        assertThat((Entity<Long>) new Order(1L)).isNotEqualTo(new Product(1L));
        assertThat((Entity<Long>) new Order(1L)).isNotEqualTo(new SubOrder(1L));
        assertThat(new SubOrder(1L)).isNotEqualTo(new Order(1L));
    }

    @Test
    void entity_should_be_equal_to_itself() {
        Order order = new Order(1L);

        assertThat(order).isEqualTo(order);
    }

    @Test
    void entity_should_not_be_equal_to_null_or_other_type() {
        Order order = new Order(1L);

        assertThat(order).isNotEqualTo(null);
        assertThat(order).isNotEqualTo("Order#1");
    }

    @Test
    void should_expose_id() {
        assertThat(new Order(7L).getId()).isEqualTo(7L);
    }

    @Test
    void should_reject_null_id() {
        assertThatThrownBy(() -> new Order(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("实体 id 不能为 null");
    }

    @Test
    void equals_hash_code_and_get_id_should_be_final() throws NoSuchMethodException {
        assertThat(Modifier.isFinal(Entity.class.getDeclaredMethod("equals", Object.class).getModifiers())).isTrue();
        assertThat(Modifier.isFinal(Entity.class.getDeclaredMethod("hashCode").getModifiers())).isTrue();
        assertThat(Modifier.isFinal(Entity.class.getDeclaredMethod("getId").getModifiers())).isTrue();
    }
}
