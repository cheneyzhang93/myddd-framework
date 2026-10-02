package org.deep361.myddd.persistence.mapstruct;

import java.time.Instant;

/** 夹具目标：比源多一个 {@code secret}——它没有任何来源，必须由 @Mapping(ignore=true) 显式放行。 */
class ProbeDst {

    private String name;
    private Instant at;
    private String secret;

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

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }
}
