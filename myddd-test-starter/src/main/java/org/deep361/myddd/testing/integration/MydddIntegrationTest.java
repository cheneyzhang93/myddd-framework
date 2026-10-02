package org.deep361.myddd.testing.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 集成测试基注解：Spring Boot 上下文 + Testcontainers 生命周期，且<b>没有 Docker 时整类跳过而不是失败</b>。
 *
 * <p>用法（按能力引入需要的容器）：
 * <pre>{@code
 * @MydddIntegrationTest
 * @ImportTestcontainers(MydddMysqlContainer.class)
 * class OrderRepositoryIT {
 *
 *     @Autowired
 *     JdbcTemplate jdbcTemplate;
 * }
 * }</pre>
 *
 * <p>刻意不包含 {@code @ImportTestcontainers}：容器导入是 opt-in 的，导入一个 classpath 上没有对应
 * {@code @ServiceConnection} 工厂的容器会让上下文刷新失败（见各 holder 类的类注释）。
 * 也刻意不包含 {@code @ActiveProfiles}：本框架不引入"测试专用 profile"这种隐式约定。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
public @interface MydddIntegrationTest {
}
