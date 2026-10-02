package org.deep361.myddd.testing.integration;

import org.deep361.myddd.testing.container.MydddMysqlContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⛔ 需要 Docker。证明 {@link MydddMysqlContainer} 经 {@code @ServiceConnection} 把容器连接信息真的交给了
 * Spring Boot（{@link JdbcConnectionDetails} → 自动装配的 {@link DataSource} → {@link JdbcTemplate} 能执行 SQL）。
 *
 * <p>本文件不依赖任何 myddd 业务模块：它证明的是测试基座本身可用。
 */
@MydddIntegrationTest
@ImportTestcontainers(MydddMysqlContainer.class)
class SampleMysqlIT {

    @Autowired
    JdbcConnectionDetails connectionDetails;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void should_connect_with_the_container_properties_and_run_sql() {
        assertThat(connectionDetails.getJdbcUrl()).startsWith("jdbc:mysql://");
        assertThat(connectionDetails.getUsername()).isNotBlank();
        assertThat(jdbcTemplate.queryForObject("select 1", Integer.class)).isEqualTo(1);
    }
}
