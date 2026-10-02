package org.deep361.myddd.testing.container;

import org.deep361.myddd.testing.image.DevboxImages;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.mysql.MySQLContainer;

/**
 * devbox 同源 MySQL 容器。业务工程的集成测试用
 * {@code @ImportTestcontainers(MydddMysqlContainer.class)} 引入，Spring Boot 负责启动它并把
 * {@code JdbcConnectionDetails} 注册进上下文（无需 {@code @DynamicPropertySource}）。
 *
 * <p>镜像引用来自 {@link DevboxImages}（唯一真源＝父 POM），所以集成测试与 devbox 手测跑的是同一个数据库版本。
 *
 * <p>前提：classpath 上有 {@code spring-boot-jdbc}（提供 MySQL 的 {@code @ServiceConnection} 工厂），
 * 由 myddd-persistence-starter 传递；缺失时上下文刷新会因容器启动失败或找不到连接信息工厂而报错。
 */
public final class MydddMysqlContainer {

    @Container
    @ServiceConnection
    public static final MySQLContainer MYSQL = new MySQLContainer(DevboxImages.dockerImage("mysql"));

    private MydddMysqlContainer() {
    }
}
