package org.deep361.myddd.testing.integration;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * 本模块集成测试的最小 Spring Boot 配置：只开自动装配，不扫组件。
 *
 * <p>{@code @SpringBootTest} 必须能在测试类包里找到 {@code @SpringBootConfiguration}，
 * 而 myddd-test-starter 本身不是应用工程，所以显式给一个。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class ItApplication {
}
