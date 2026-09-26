package org.deep361.myddd.archunit.fixture.violating.rule4.infrastructure;

public class OrderStore {

    public String load(String id) {
        return "stored:" + id;
    }
}
