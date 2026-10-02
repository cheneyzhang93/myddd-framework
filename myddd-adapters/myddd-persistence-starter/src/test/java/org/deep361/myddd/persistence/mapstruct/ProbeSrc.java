package org.deep361.myddd.persistence.mapstruct;

import java.time.Instant;

/** 夹具源：两个"有来源"的属性。 */
class ProbeSrc {

    private String name;
    private Instant at;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }
}
