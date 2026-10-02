package org.deep361.myddd.persistence.autoconfigure;

import org.deep361.myddd.domain.outbox.OutboxRecord;
import org.deep361.myddd.domain.outbox.OutboxStatus;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.common.page.PageQuery;
import org.deep361.myddd.kernel.common.page.PageResult;
import org.deep361.myddd.persistence.ddl.DdlReference;
import org.deep361.myddd.persistence.outbox.JdbcOutboxDeadLetterStore;
import org.deep361.myddd.persistence.outbox.OutboxDeadLetterStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 死信读侧装配面（{@code myddd.persistence.outbox.dead-letter.*}）的语义。
 *
 * <p>与 {@link MydddOutboxStoreAutoConfigurationTest} 同一分工：本类只管"bean 在不在容器里、以什么身份在、
 * 开关与依赖缺失时怎么失败、配置值有没有真的进到那个对象里"。SQL 语义在
 * {@code JdbcOutboxDeadLetterStoreTest}（D 组）取证，MySQL 自己的语义在 Task 20 的容器层取证。
 *
 * <p><b>开关只有一把</b>：{@code myddd.persistence.outbox.enabled} 同时管写侧与读侧（"这张表在用"是一件事），
 * 所以下面每条正向用例都得先把这把开关打开——包括本类的装配类排在写侧之后这一条：装配顺序不影响
 * 条件是否成立，影响的是同一个 {@code myddd.persistence.outbox.table} 值被两侧各自读到。
 */
class MydddOutboxDeadLetterAutoConfigurationTest {

    private static final String ENABLE = "myddd.persistence.outbox.enabled=true";
    private static final AtomicInteger DATABASES = new AtomicInteger();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddOutboxDeadLetterAutoConfiguration.class));

    // ------------------------------------------------------------- 开关与退让

    @Test
    void should_not_assemble_the_read_side_when_the_outbox_switch_is_absent() {
        runner.withBean(DataSource.class, MydddOutboxDeadLetterAutoConfigurationTest::h2)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(h2()))
                .run(context -> assertThat(context).doesNotHaveBean(OutboxDeadLetterStore.class));
    }

    @Test
    void should_assemble_the_read_side_when_the_switch_is_on() {
        runner.withPropertyValues(ENABLE)
                .withBean(DataSource.class, MydddOutboxDeadLetterAutoConfigurationTest::h2)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(h2()))
                .run(context -> {
                    assertThat(context).hasSingleBean(JdbcOutboxDeadLetterStore.class);
                    assertThat(context).hasSingleBean(OutboxDeadLetterStore.class);
                    assertThat(context).hasNotFailed();
                });
    }

    @Test
    void should_back_off_when_the_business_provides_its_own_dead_letter_query() {
        // 业务把死信归档到 ES 或另一张表时，框架那个实现必须让位，而不是变成两个候选让注入失败。
        OutboxDeadLetterStore business = new StubDeadLetterStore();

        runner.withPropertyValues(ENABLE)
                .withBean(DataSource.class, MydddOutboxDeadLetterAutoConfigurationTest::h2)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(h2()))
                .withBean(OutboxDeadLetterStore.class, () -> business)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(JdbcOutboxDeadLetterStore.class);
                    assertThat(context.getBean(OutboxDeadLetterStore.class)).isSameAs(business);
                });
    }

    @Test
    void should_back_off_when_the_persistence_capability_is_disabled() {
        runner.withPropertyValues(ENABLE, "myddd.persistence.enabled=false")
                .withBean(DataSource.class, MydddOutboxDeadLetterAutoConfigurationTest::h2)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(h2()))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(OutboxDeadLetterStore.class);
                });
    }

    // ------------------------------------------------------- 缺依赖：快速失败而不是静默退让

    @Test
    void should_fail_fast_when_no_datasource_is_available() {
        runner.withPropertyValues(ENABLE).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context).getFailure()
                    .isInstanceOf(BeanCreationException.class)
                    .hasRootCauseInstanceOf(FrameworkException.class)
                    .hasStackTraceContaining("需要容器里有一个 DataSource")
                    .hasStackTraceContaining("死信列表无从查起");
        });
    }

    @Test
    void should_fail_fast_when_no_transaction_manager_is_available() {
        // 读侧退化得起（两条只读 SELECT 自动提交最多让翻页短暂不一致），但装配期不赌：
        // 开关是业务显式打开的，缺依赖就该在启动那一刻说清楚，而不是留一个随时会翻乱的组合。
        runner.withPropertyValues(ENABLE)
                .withBean(DataSource.class, MydddOutboxDeadLetterAutoConfigurationTest::h2)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().hasRootCauseInstanceOf(FrameworkException.class)
                            .hasStackTraceContaining("需要容器里有一个 PlatformTransactionManager");
                });
    }

    // --------------------------------------------------------------- 候选歧义

    @Test
    void should_fail_fast_when_two_datasources_are_declared_without_a_primary() {
        runner.withPropertyValues(ENABLE)
                .withUserConfiguration(TwoPlainDataSources.class)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(h2()))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .hasRootCauseInstanceOf(NoUniqueBeanDefinitionException.class)
                            .hasStackTraceContaining("DataSource 有多个候选且没有 @Primary")
                            // 读侧的消息说"查哪个库"，写侧的说"往哪个库写"：同一个形状、两条不同的出路
                            .hasStackTraceContaining("死信读侧不知道该查哪个库");
                });
    }

    @Test
    void should_use_the_primary_candidates_when_several_are_declared() {
        runner.withPropertyValues(ENABLE)
                .withUserConfiguration(PrimaryPair.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JdbcOutboxDeadLetterStore.class);
                });
    }

    // ------------------------------------------------------------------ 配置值

    @Test
    void should_default_the_query_limit_to_two_hundred() {
        runner.withPropertyValues(ENABLE)
                .withBean(DataSource.class, MydddOutboxDeadLetterAutoConfigurationTest::h2)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(h2()))
                .run(context -> assertThat(context.getBean(JdbcOutboxDeadLetterStore.class).queryLimit())
                        .as("一次误点不该把整张表的死信同时拽回队列，也不该逼每个工程都显式配一遍")
                        .isEqualTo(200));
    }

    @Test
    void should_bind_the_configured_query_limit_into_the_bean() {
        runner.withPropertyValues(ENABLE, "myddd.persistence.outbox.dead-letter.query-limit=7")
                .withBean(DataSource.class, MydddOutboxDeadLetterAutoConfigurationTest::h2)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(h2()))
                .run(context -> {
                    JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                    createSchema(jdbc, DdlReference.OUTBOX_TABLE);
                    insertDead(jdbc, DdlReference.OUTBOX_TABLE, "d1");
                    insertDead(jdbc, DdlReference.OUTBOX_TABLE, "d2");
                    JdbcOutboxDeadLetterStore store = context.getBean(JdbcOutboxDeadLetterStore.class);
                    assertThat(store.queryLimit()).isEqualTo(7);
                    PageResult<OutboxRecord> page = store.findDead(null, null, PageQuery.of(1, 500));
                    assertThat(page.pageSize())
                            .as("页大小与批量重放共用同一个上限：一个键管住两种\"一口气太多\"")
                            .isEqualTo(7L);
                    assertThat(page.total()).isEqualTo(2L);
                });
    }

    @Test
    void should_fail_fast_when_the_query_limit_is_below_one() {
        // 0 页大小不会报错，只会永远查出空列表——那种静默比启动失败难查得多，所以在装配期就拦掉。
        runner.withPropertyValues(ENABLE, "myddd.persistence.outbox.dead-letter.query-limit=0")
                .withBean(DataSource.class, MydddOutboxDeadLetterAutoConfigurationTest::h2)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(h2()))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().hasRootCauseInstanceOf(FrameworkException.class)
                            .hasStackTraceContaining("myddd.persistence.outbox.dead-letter.query-limit 必须 ≥ 1");
                });
    }

    @Test
    void should_reject_an_invalid_table_name_when_the_switch_is_on() {
        runner.withPropertyValues(ENABLE, "myddd.persistence.outbox.table=dead; drop")
                .withBean(DataSource.class, MydddOutboxDeadLetterAutoConfigurationTest::h2)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(h2()))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().hasRootCauseInstanceOf(FrameworkException.class)
                            .hasStackTraceContaining("[a-z0-9_]{1,64}");
                });
    }

    @Test
    void should_query_the_configured_table() {
        String custom = "my_order_dead_letter";

        runner.withPropertyValues(ENABLE, "myddd.persistence.outbox.table=" + custom)
                .withBean(DataSource.class, MydddOutboxDeadLetterAutoConfigurationTest::h2)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(h2()))
                .run(context -> {
                    JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                    // 两张表都建：只建自定义表的话，"读错表"这一类变异会表现为语法错误而不是读到了另一张表
                    createSchema(jdbc, DdlReference.OUTBOX_TABLE);
                    createSchema(jdbc, custom);
                    insertDead(jdbc, custom, "mine");
                    insertDead(jdbc, DdlReference.OUTBOX_TABLE, "theirs");

                    assertThat(context.getBean(OutboxDeadLetterStore.class)
                            .findDead(null, null, PageQuery.of(1, 10)).items())
                            .extracting(OutboxRecord::eventId).containsExactly("mine");
                    assertThat(context.getBean(JdbcOutboxDeadLetterStore.class).requeue(List.of("theirs"),
                            Instant.ofEpochMilli(1_700_000_000_000L))).isZero();
                });
    }

    // -------------------------------------------------------------------- 夹具

    private static DataSource h2() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:deadlettercfg-" + DATABASES.incrementAndGet() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setDriverClassName("org.h2.Driver");
        return ds;
    }

    private static void createSchema(JdbcTemplate jdbc, String table) {
        Arrays.stream(DdlReference.read(DdlReference.OUTBOX_DDL).replace(DdlReference.OUTBOX_TABLE, table)
                        .split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .forEach(jdbc::execute);
    }

    private static void insertDead(JdbcTemplate jdbc, String table, String eventId) {
        Instant created = Instant.ofEpochMilli(1_700_000_000_000L);
        jdbc.update("insert into " + table + " (event_id, event_type, aggregate_type, aggregate_id, trace_id,"
                        + " occurred_at, channel, payload, status, retry_count, next_retry_at, last_error,"
                        + " created_at, dispatch_lease_until)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                eventId, "org.deep361.sample.OrderCancelled", "org.deep361.sample.Order", "order-" + eventId,
                "trace-" + eventId, Timestamp.from(created.minusSeconds(1)), "order-events", "{\"n\":1}",
                OutboxStatus.DEAD.name(), 3, Timestamp.from(created.plusSeconds(60)), "boom",
                Timestamp.from(created), Timestamp.from(created.plusSeconds(300)));
    }

    /** 两个数据源、无 {@code @Primary}：按类型解析必须报歧义，而不是随机挑一个库查死信。 */
    @Configuration(proxyBeanMethods = false)
    static class TwoPlainDataSources {

        @Bean
        DataSource first() {
            return h2();
        }

        @Bean
        DataSource second() {
            return h2();
        }
    }

    /** {@code @Primary} 生效的正面对照。 */
    @Configuration(proxyBeanMethods = false)
    static class PrimaryPair {

        @Bean
        @Primary
        DataSource main() {
            return h2();
        }

        @Bean
        DataSource other() {
            return h2();
        }

        @Bean
        @Primary
        PlatformTransactionManager mainTx(DataSource main) {
            return new DataSourceTransactionManager(main);
        }

        @Bean
        PlatformTransactionManager otherTx(DataSource other) {
            return new DataSourceTransactionManager(other);
        }
    }

    /** 只为"业务自己实现读侧 SPI"这一条用例存在：三个方法都不必真跑 SQL。 */
    private static final class StubDeadLetterStore implements OutboxDeadLetterStore {

        @Override
        public PageResult<OutboxRecord> findDead(Instant from, Instant to, PageQuery page) {
            return PageResult.empty(1L, 10L);
        }

        @Override
        public int requeue(List<String> eventIds, Instant now) {
            return 0;
        }

        @Override
        public int queryLimit() {
            return 10;
        }
    }
}
