package org.deep361.myddd.testing.reset;

import org.deep361.myddd.testing.container.MydddMysqlContainer;
import org.deep361.myddd.testing.integration.ItApplication;
import org.deep361.myddd.testing.integration.MydddIntegrationTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⛔ 需要 Docker。端到端证明三件事：真业务表被清空、Flyway 版本表活着、排除表不动。
 *
 * <p>{@code @Order} 保证两个方法按"先灌数据、再验证清理"的顺序跑：清理发生在每个用例之前，
 * 所以第二个用例看到的状态正是第一个用例留下的数据被清掉之后的样子。
 */
@MydddIntegrationTest
@DataReset(exclude = "seeded")
@ContextConfiguration(classes = ItApplication.class)
@ImportTestcontainers(MydddMysqlContainer.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DataResetIT {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @Order(1)
    void should_seed_tables_for_the_next_case() {
        jdbcTemplate.execute("create table if not exists orders (id bigint primary key)");
        jdbcTemplate.execute("create table if not exists seeded (id bigint primary key)");
        jdbcTemplate.execute("create table if not exists flyway_schema_history (installed_rank int primary key)");
        jdbcTemplate.update("insert into orders values (1), (2)");
        jdbcTemplate.update("insert into seeded values (1)");
        jdbcTemplate.update("insert into flyway_schema_history values (1)");

        assertThat(jdbcTemplate.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(2);
    }

    @Test
    @Order(2)
    void should_find_business_tables_empty_and_exempt_ones_intact() {
        assertThat(jdbcTemplate.queryForObject("select count(*) from orders", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from seeded", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from flyway_schema_history", Integer.class))
                .isEqualTo(1);
    }
}
