package org.deep361.myddd.testing.container;

import com.redis.testcontainers.RedisContainer;
import org.deep361.myddd.testing.image.DevboxImages;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;

/**
 * devbox 同源 Redis 容器（{@code @ServiceConnection} → {@code DataRedisConnectionDetails}）。
 *
 * <p>前提：classpath 上有 {@code spring-boot-data-redis}，由 myddd-cache-starter /
 * myddd-consistency-starter 传递。不需要 Redis 能力的测试不要引入本类。
 */
public final class MydddRedisContainer {

    @Container
    @ServiceConnection
    public static final RedisContainer REDIS = new RedisContainer(DevboxImages.dockerImage("redis"));

    private MydddRedisContainer() {
    }
}
