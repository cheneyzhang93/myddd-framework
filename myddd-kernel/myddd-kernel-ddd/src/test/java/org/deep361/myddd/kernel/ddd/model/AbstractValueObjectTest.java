package org.deep361.myddd.kernel.ddd.model;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractValueObjectTest {

    static class Money extends AbstractValueObject {
        private final long cents;
        private final String currency;

        Money(long cents, String currency) {
            this.cents = cents;
            this.currency = currency;
        }
    }

    static class Weight extends AbstractValueObject {
        private final long cents;
        private final String currency;

        Weight(long cents, String currency) {
            this.cents = cents;
            this.currency = currency;
        }
    }

    static class DiscountedMoney extends Money {
        private final int discountRate;

        DiscountedMoney(long cents, String currency, int discountRate) {
            super(cents, currency);
            this.discountRate = discountRate;
        }
    }

    static class Tagged extends AbstractValueObject {
        private static final String TAG = "static-must-be-ignored";
        private final int value;

        Tagged(int value) {
            this.value = value;
        }
    }

    static class Snapshot extends AbstractValueObject {
        private final int value;
        private final transient String memo;

        Snapshot(int value, String memo) {
            this.value = value;
            this.memo = memo;
        }
    }

    class InnerValue extends AbstractValueObject {
        private final int value;

        InnerValue(int value) {
            this.value = value;
        }
    }

    @Test
    void should_be_equal_when_all_fields_equal() {
        assertThat(new Money(100L, "CNY")).isEqualTo(new Money(100L, "CNY"));
    }

    @Test
    void should_have_same_hash_code_when_equal() {
        assertThat(new Money(100L, "CNY")).hasSameHashCodeAs(new Money(100L, "CNY"));
    }

    @Test
    void should_not_be_equal_when_field_differs() {
        assertThat(new Money(100L, "CNY")).isNotEqualTo(new Money(100L, "USD"));
        assertThat(new Money(100L, "CNY")).doesNotHaveSameHashCodeAs(new Money(100L, "USD"));
    }

    @Test
    void should_not_be_equal_across_types_with_same_fields() {
        assertThat((AbstractValueObject) new Money(100L, "CNY")).isNotEqualTo(new Weight(100L, "CNY"));
    }

    @Test
    void should_not_be_equal_to_null_or_other_type() {
        Money money = new Money(100L, "CNY");

        assertThat(money).isNotEqualTo(null);
        assertThat(money).isNotEqualTo("100 CNY");
    }

    @Test
    void should_be_equal_to_itself() {
        Money money = new Money(100L, "CNY");

        assertThat(money).isEqualTo(money);
    }

    @Test
    void should_compare_inherited_fields() {
        assertThat(new DiscountedMoney(100L, "CNY", 90)).isEqualTo(new DiscountedMoney(100L, "CNY", 90));
        assertThat(new DiscountedMoney(100L, "CNY", 90)).isNotEqualTo(new DiscountedMoney(100L, "CNY", 80));
        assertThat((Money) new DiscountedMoney(100L, "CNY", 90)).isNotEqualTo(new Money(100L, "CNY"));
        assertThat(new DiscountedMoney(100L, "CNY", 90)).isNotEqualTo(new DiscountedMoney(101L, "CNY", 90));
        assertThat(new DiscountedMoney(100L, "CNY", 90).toString()).contains("cents=100", "currency=CNY", "discountRate=90");
    }

    @Test
    void should_render_fields_in_to_string() {
        assertThat(new Money(100L, "CNY").toString()).contains("Money", "cents=100", "currency=CNY");
    }

    @Test
    void should_ignore_transient_fields() {
        assertThat(new Snapshot(1, "first")).isEqualTo(new Snapshot(1, "second"));
        assertThat(new Snapshot(1, "first").toString()).doesNotContain("memo");
        assertThat(new Snapshot(1, "first")).hasSameHashCodeAs(new Snapshot(1, "second"));
    }

    @Test
    void should_ignore_static_fields() {
        assertThat(new Tagged(1).toString()).contains("value=1").doesNotContain("TAG");
    }

    @Test
    void should_ignore_synthetic_enclosing_reference() {
        AbstractValueObjectTest other = new AbstractValueObjectTest();

        assertThat(new InnerValue(1)).isEqualTo(other.new InnerValue(1));
        assertThat(new InnerValue(1)).hasSameHashCodeAs(other.new InnerValue(1));
    }

    @Test
    void should_survive_serialization_round_trip() throws Exception {
        Money money = new Money(100L, "CNY");

        assertThat(roundTrip(money)).isEqualTo(money);
    }

    private static Object roundTrip(Object value) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return in.readObject();
        }
    }
}
