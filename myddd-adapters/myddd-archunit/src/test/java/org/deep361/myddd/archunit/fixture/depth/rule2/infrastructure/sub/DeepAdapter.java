package org.deep361.myddd.archunit.fixture.depth.rule2.infrastructure.sub;

import java.util.UUID;

/** 深度夹具：infrastructure 深层子包里的适配器实现。 */
public class DeepAdapter {

    public String id() {
        return UUID.randomUUID().toString();
    }
}
