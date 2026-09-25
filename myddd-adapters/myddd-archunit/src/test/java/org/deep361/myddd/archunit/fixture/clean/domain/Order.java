package org.deep361.myddd.archunit.fixture.clean.domain;

import java.util.Objects;

public class Order {

    private final String id;
    private Amount amount;

    public Order(String id, Amount amount) {
        this.id = Objects.requireNonNull(id, "id");
        this.amount = Objects.requireNonNull(amount, "amount");
    }

    public String id() {
        return id;
    }

    public Amount amount() {
        return amount;
    }

    public void changeAmount(Amount next) {
        this.amount = Objects.requireNonNull(next, "next");
    }
}
