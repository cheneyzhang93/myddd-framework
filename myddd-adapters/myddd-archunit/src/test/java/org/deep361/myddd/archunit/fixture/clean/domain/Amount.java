package org.deep361.myddd.archunit.fixture.clean.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record Amount(BigDecimal value) {

    public Amount {
        Objects.requireNonNull(value, "value");
        if (value.signum() < 0) {
            throw new IllegalArgumentException("金额不得为负: " + value);
        }
    }

    public Amount plus(Amount other) {
        return new Amount(value.add(other.value));
    }
}
