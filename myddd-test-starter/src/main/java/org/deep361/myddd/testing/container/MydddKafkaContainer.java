package org.deep361.myddd.testing.container;

import org.deep361.myddd.testing.image.DevboxImages;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.KafkaContainer;

/**
 * devbox 同源 Kafka（KRaft 单节点）容器，供消息类集成测试使用（{@code MessagePort} 的 Kafka 实现在 Plan 4）。
 *
 * <p>前提：classpath 上有 {@code spring-boot-kafka}。使用 {@code org.testcontainers.kafka.KafkaContainer}，
 * 不是已废弃的 {@code org.testcontainers.containers.KafkaContainer}。
 */
public final class MydddKafkaContainer {

    @Container
    @ServiceConnection
    public static final KafkaContainer KAFKA = new KafkaContainer(DevboxImages.dockerImage("kafka"));

    private MydddKafkaContainer() {
    }
}
