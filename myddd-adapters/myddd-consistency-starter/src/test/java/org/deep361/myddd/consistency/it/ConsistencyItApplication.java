package org.deep361.myddd.consistency.it;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * 真 Redis 验收用例的最小 Spring Boot 配置（Plan 3 Task 24）。
 *
 * <p>{@code @SpringBootTest} 必须能在测试类所在包里找到一个 {@code @SpringBootConfiguration}，而
 * myddd-consistency-starter 是库模块、不是应用工程，所以显式给一个（与缓存侧 {@code CacheItApplication}、
 * 持久化侧 {@code PersistenceItApplication} 同一手法）。除自动装配外不扫任何组件：
 * 本类的全部接线证据都要来自 {@code AutoConfiguration.imports}，自己扫组件等于替自动装配造假。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class ConsistencyItApplication {
}
