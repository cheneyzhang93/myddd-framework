package org.deep361.myddd.cache.it;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * 真 Redis 验收用例的最小 Spring Boot 配置（Plan 3 Task 23）。
 *
 * <p>{@code @SpringBootTest} 必须能在测试类所在包里找到一个 {@code @SpringBootConfiguration}，而
 * myddd-cache-starter 是库模块、不是应用工程，所以显式给一个（与持久化侧 {@code PersistenceItApplication}
 * 同一手法）。除自动装配外什么都不扫组件：本类的全部接线证据都要来自 {@code AutoConfiguration.imports}，
 * 自己扫业务组件等于替自动装配造假。
 *
 * <p>{@code @EnableCaching} 也<b>不在</b>这里：开不开缓存代理是业务工程的决定（框架不接管，见装配类 javadoc），
 * 所以它写在 {@code CacheRedisIT} 自己的测试配置里。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class CacheItApplication {
}
