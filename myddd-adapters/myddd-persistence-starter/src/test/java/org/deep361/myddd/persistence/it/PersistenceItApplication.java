package org.deep361.myddd.persistence.it;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * 真库验收用例的最小 Spring Boot 配置。
 *
 * <p>{@code @SpringBootTest} 必须能在测试类所在包里找到一个 {@code @SpringBootConfiguration}，
 * 而 myddd-persistence-starter 是库模块、不是应用工程，所以显式给一个（与 Task 5 的
 * {@code ItApplication} 同一手法）。除自动装配外什么都不扫组件：本类的全部接线证据都要来自
 * {@code AutoConfiguration.imports}，自己扫业务组件等于替自动装配造假。
 *
 * <p>{@code @MapperScan} 只扫本包，服务 {@link AuditMySqlIT} 的夹具 Mapper。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@MapperScan(basePackageClasses = PersistenceItApplication.class)
public class PersistenceItApplication {
}
