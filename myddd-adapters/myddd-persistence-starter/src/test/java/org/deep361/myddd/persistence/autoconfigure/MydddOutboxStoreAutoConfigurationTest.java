package org.deep361.myddd.persistence.autoconfigure;

import org.deep361.myddd.domain.outbox.OutboxRecord;
import org.deep361.myddd.domain.outbox.OutboxStore;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.persistence.ddl.DdlReference;
import org.deep361.myddd.persistence.outbox.JdbcOutboxStore;
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
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox 存储装配面（{@code myddd.persistence.outbox.*}）的语义。
 *
 * <p><b>这一层不需要 Docker，也基本不建表</b>：本类只问"bean 在不在容器里、以什么身份在、开关和依赖缺失时怎么
 * 失败"。{@link JdbcOutboxStore} 的构造只拼 SQL 文本与校验表名，一条语句都不执行；真正跑 SQL 的语义在
 * {@code JdbcOutboxStoreTest}（S 组，H2 上按参考 DDL 建表）取证，MySQL 自己的语义在 Task 20 取证。唯一的例外
 * 是最后一条用例——它要证明"配置里的表名真的进了 SQL"，所以起了一个真 H2 并建了两张表。
 *
 * <p>每个用例都用唯一库名（{@code outboxcfg-<n>}）：{@code DB_CLOSE_DELAY=-1} 的内存库活到 JVM 结束，
 * 共名会让上一个用例建的表渗进下一个。
 */
class MydddOutboxStoreAutoConfigurationTest {

    private static final String ENABLE = "myddd.persistence.outbox.enabled=true";
    private static final AtomicInteger DATABASES = new AtomicInteger();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddOutboxStoreAutoConfiguration.class));

    // ------------------------------------------------------------- 开关与退让

    @Test
    void should_not_assemble_the_store_when_the_switch_is_absent() {
        runner.withBean(DataSource.class, MydddOutboxStoreAutoConfigurationTest::h2)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(h2()))
                .run(context -> assertThat(context).doesNotHaveBean(OutboxStore.class));
    }

    @Test
    void should_assemble_the_store_when_the_switch_is_on() {
        runner.withPropertyValues(ENABLE)
                .withBean(DataSource.class, MydddOutboxStoreAutoConfigurationTest::h2)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(h2()))
                .run(context -> {
                    assertThat(context).hasSingleBean(JdbcOutboxStore.class);
                    assertThat(context).hasSingleBean(OutboxStore.class);
                });
    }

    @Test
    void should_back_off_when_the_business_provides_its_own_outbox_store() {
        OutboxStore business = new StubStore();

        runner.withPropertyValues(ENABLE)
                .withBean(DataSource.class, MydddOutboxStoreAutoConfigurationTest::h2)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(h2()))
                .withBean(OutboxStore.class, () -> business)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(JdbcOutboxStore.class);
                    assertThat(context.getBean(OutboxStore.class)).isSameAs(business);
                });
    }

    @Test
    void should_back_off_when_the_persistence_capability_is_disabled() {
        runner.withPropertyValues(ENABLE, "myddd.persistence.enabled=false")
                .withBean(DataSource.class, MydddOutboxStoreAutoConfigurationTest::h2)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(h2()))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(OutboxStore.class);
                });
    }

    // ------------------------------------------------------- 缺依赖：快速失败而不是静默退让

    @Test
    void should_fail_fast_when_no_datasource_is_available() {
        // 钉的是"这里刻意不用 @ConditionalOnBean"这条设计决定：开关是业务显式打开的，此时缺依赖必须让
        // 上下文启动失败——静默退让的表现是"启动成功、但领域事件永不投递"，那是查不出原因的丢消息。
        runner.withPropertyValues(ENABLE).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context).getFailure()
                    .isInstanceOf(BeanCreationException.class)
                    .hasRootCauseInstanceOf(FrameworkException.class)
                    .hasStackTraceContaining("需要容器里有一个 DataSource")
                    .hasStackTraceContaining("出路二选一");
        });
    }

    @Test
    void should_fail_fast_when_no_transaction_manager_is_available() {
        runner.withPropertyValues(ENABLE)
                .withBean(DataSource.class, MydddOutboxStoreAutoConfigurationTest::h2)
                .run(context -> {
                    assertThat(context).hasFailed();
                    // 只报 PlatformTransactionManager：DataSource 那一侧已经解析成功了
                    assertThat(context).getFailure().hasRootCauseInstanceOf(FrameworkException.class)
                            .hasStackTraceContaining("需要容器里有一个 PlatformTransactionManager")
                            .hasStackTraceContaining("DataSourceTransactionManager");
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
                    // 歧义这一类不能用 hasRootCauseInstanceOf(FrameworkException)：Spring 在工厂方法外再包了
                    // BeanInstantiationException，而我们又把 NoUniqueBeanDefinitionException 当作 cause 挂在
                    // FrameworkException 上（保留原始判定依据），所以根因是 Spring 的那个。
                    assertThat(context).getFailure()
                            .hasRootCauseInstanceOf(NoUniqueBeanDefinitionException.class)
                            .hasStackTraceContaining("DataSource 有多个候选且没有 @Primary")
                            .hasStackTraceContaining("不知道该往哪个库写");
                });
    }

    @Test
    void should_fail_fast_when_two_transaction_managers_are_declared_without_a_primary() {
        runner.withPropertyValues(ENABLE)
                .withBean(DataSource.class, MydddOutboxStoreAutoConfigurationTest::h2)
                .withUserConfiguration(TwoPlainTransactionManagers.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .hasRootCauseInstanceOf(NoUniqueBeanDefinitionException.class)
                            .hasStackTraceContaining("PlatformTransactionManager 有多个候选且没有 @Primary");
                });
    }

    @Test
    void should_use_the_primary_candidates_when_several_are_declared() {
        // 上一条规则的正面对照：歧义只该在"没有主候选"时出现，有 @Primary 就必须照它挑，而不是报错。
        runner.withPropertyValues(ENABLE)
                .withUserConfiguration(PrimaryPair.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JdbcOutboxStore.class);
                });
    }

    // ------------------------------------------------------------------ 表名

    @Test
    void should_reject_an_invalid_table_name_when_the_switch_is_on() {
        runner.withPropertyValues(ENABLE, "myddd.persistence.outbox.table=myddd_outbox; drop")
                .withBean(DataSource.class, MydddOutboxStoreAutoConfigurationTest::h2)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(h2()))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().hasRootCauseInstanceOf(FrameworkException.class)
                            .hasStackTraceContaining("[a-z0-9_]{1,64}")
                            .hasStackTraceContaining("myddd.persistence.outbox.table");
                });
    }

    @Test
    void should_not_validate_the_table_name_when_the_switch_is_off() {
        // 校验长在 store 的构造器里（那是唯一把这个值拼进 SQL 文本的地方），所以开关不开时连非法表名都不报错：
        // 一个没被装配的键没有资格让应用启动失败。
        runner.withPropertyValues("myddd.persistence.outbox.table=Not A Table")
                .withBean(DataSource.class, MydddOutboxStoreAutoConfigurationTest::h2)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(OutboxStore.class);
                });
    }

    @Test
    void should_write_into_the_configured_table() {
        String table = "my_order_outbox";

        runner.withPropertyValues(ENABLE, "myddd.persistence.outbox.table=" + table)
                .withBean(DataSource.class, MydddOutboxStoreAutoConfigurationTest::h2)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(h2()))
                .run(context -> {
                    JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                    // 两张表都建：只建自定义表的话，"写错表"这一类变异会表现为语法错误而不是写进了另一张表
                    createSchema(jdbc, DdlReference.OUTBOX_TABLE);
                    createSchema(jdbc, table);

                    context.getBean(JdbcOutboxStore.class).save(record("e1"));

                    assertThat(jdbc.query("select event_id from " + table, (rs, n) -> rs.getString(1)))
                            .containsExactly("e1");
                    assertThat(jdbc.query("select event_id from " + DdlReference.OUTBOX_TABLE,
                            (rs, n) -> rs.getString(1))).isEmpty();
                });
    }

    // -------------------------------------------------------------------- 夹具

    private static DataSource h2() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:outboxcfg-" + DATABASES.incrementAndGet() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
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

    private static OutboxRecord record(String eventId) {
        Instant now = Instant.ofEpochMilli(1_700_000_000_000L);
        return OutboxRecord.pending(eventId, "org.deep361.sample.OrderCreated", "org.deep361.sample.Order",
                "order-" + eventId, now.minusSeconds(1), "trace-" + eventId, "order-events", "{\"n\":1}", now);
    }

    /** 两个数据源、无 {@code @Primary}：按类型解析必须报歧义，而不是随机挑一个库写 Outbox。 */
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

    /** 两个事务管理器、无 {@code @Primary}：同上一条，缺的是认领事务那一侧的歧义。 */
    @Configuration(proxyBeanMethods = false)
    static class TwoPlainTransactionManagers {

        @Bean
        PlatformTransactionManager first(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        PlatformTransactionManager second(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
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

    /** 只为"业务自己实现 SPI"这一条用例存在：三个方法都不必真跑 SQL。 */
    private static final class StubStore implements OutboxStore {

        @Override
        public void save(OutboxRecord record) {
        }

        @Override
        public List<OutboxRecord> claimDispatchable(Instant now, Instant leaseUntil, int limit) {
            return List.of();
        }

        @Override
        public void update(OutboxRecord record) {
        }
    }
}
