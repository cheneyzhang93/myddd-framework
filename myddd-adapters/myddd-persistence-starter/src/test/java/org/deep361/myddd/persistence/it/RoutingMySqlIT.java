package org.deep361.myddd.persistence.it;

import org.deep361.myddd.persistence.ddl.DdlReference;
import org.deep361.myddd.persistence.routing.UseDataSource;
import org.deep361.myddd.testing.container.MydddMysqlContainer;
import org.deep361.myddd.testing.integration.MydddIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由数据源在真实 MySQL 上的归属验收（Plan 3 Task 20，兑现 Task 17 设计要点 9/11 与 Task 16 的"两份 DDL"）。
 *
 * <p>Task 17 在本机 h2 上用 {@code Connection.getMetaData().getURL()} 做归因；真库要换成"查各自的库"：
 * 同一个 MySQL 实例里建两个 schema，断言带 {@link UseDataSource} 的写入只出现在第二个 schema 里。
 * 参考 DDL（Outbox 与幂等表）也在真 MySQL 上各执行一遍——这是 Task 16 明确留给本任务的证据。
 *
 * <p><b>为什么这里显式 {@code start()}</b>：路由模式的连接串是我们在上下文刷新<b>之前</b>就要绑定的
 * 属性（{@link DynamicPropertySource}），而 Testcontainers 的映射端口只有容器起来之后才存在。
 * {@code start()} 对已运行容器是 no-op，与 {@code @ImportTestcontainers} 的生命周期不冲突。
 *
 * <p><b>两个 schema 要 root 建、root 授权</b>（{@link #provisionSchemas()}）：容器的业务账号只对容器自带的
 * 那个库有权限，而本类要把写入分到 <code>myddd_primary</code> 与 <code>myddd_report</code> 两个库上归因。
 */
@MydddIntegrationTest
@ImportTestcontainers(MydddMysqlContainer.class)
class RoutingMySqlIT {

    private static final String PRIMARY_SCHEMA = "myddd_primary";
    private static final String REPORT_SCHEMA = "myddd_report";
    private static final String PROBE_TABLE = "it_routing_probe";

    static {
        MydddMysqlContainer.MYSQL.start();
        provisionSchemas();
    }

    /**
     * 建库 + 授权必须在上下文刷新前完成：路由数据源起池子时就要连得上这两个 schema。
     *
     * <p>缺这一步时本类三条用例全栽在 {@code Access denied for user 'test'@'%' to database
     * 'myddd_primary'}——URL 上的 {@code createDatabaseIfNotExist=true} 需要的 CREATE 权限并不在业务账号
     * 的授权范围内。连的是容器自己的 root：{@code MySQLContainer#configure} 把 {@code MYSQL_ROOT_PASSWORD}
     * 设成容器的 password 字段（字节码实测；{@code MYSQL_ALLOW_EMPTY_PASSWORD} 只在 username 恰为 root 时
     * 才加），所以口令取 {@code getPassword()}，不引入任何外部凭据。
     */
    private static void provisionSchemas() {
        try (Connection root = DriverManager.getConnection(serverPrefix() + "/mysql", "root",
                MydddMysqlContainer.MYSQL.getPassword());
                Statement statement = root.createStatement()) {
            for (String schema : List.of(PRIMARY_SCHEMA, REPORT_SCHEMA)) {
                statement.execute("create database if not exists " + schema);
                statement.execute("grant all privileges on " + schema + ".* to '"
                        + MydddMysqlContainer.MYSQL.getUsername() + "'@'%'");
            }
            statement.execute("flush privileges");
        } catch (SQLException failed) {
            throw new IllegalStateException("路由用例需要两个已授权的业务 schema：容器建库失败", failed);
        }
    }

    private static String serverPrefix() {
        String declared = MydddMysqlContainer.MYSQL.getJdbcUrl();
        return declared.substring(0, declared.indexOf('/', declared.indexOf("//") + 2));
    }

    private static String urlFor(String schema) {
        return serverPrefix() + "/" + schema + "?createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true"
                + "&connectionTimeZone=UTC&characterEncoding=UTF-8";
    }

    @DynamicPropertySource
    static void routeTwoSchemas(DynamicPropertyRegistry registry) {
        String user = MydddMysqlContainer.MYSQL.getUsername();
        String password = MydddMysqlContainer.MYSQL.getPassword();
        registry.add("myddd.persistence.datasources.primary.url", () -> urlFor(PRIMARY_SCHEMA));
        registry.add("myddd.persistence.datasources.primary.username", () -> user);
        registry.add("myddd.persistence.datasources.primary.password", () -> password);
        registry.add("myddd.persistence.datasources.report.url", () -> urlFor(REPORT_SCHEMA));
        registry.add("myddd.persistence.datasources.report.username", () -> user);
        registry.add("myddd.persistence.datasources.report.password", () -> password);
        registry.add("myddd.persistence.default-datasource", () -> "primary");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ReportProbe reportProbe;

    @BeforeEach
    void prepareSchemas() throws SQLException {
        try (Connection primary = open(PRIMARY_SCHEMA); Connection report = open(REPORT_SCHEMA)) {
            for (Connection connection : List.of(primary, report)) {
                connection.createStatement().execute("create table if not exists " + PROBE_TABLE
                        + " (note varchar(64) not null) engine = innodb");
                connection.createStatement().execute("truncate table " + PROBE_TABLE);
                connection.createStatement().execute("drop table if exists " + DdlReference.OUTBOX_TABLE);
                connection.createStatement().execute("drop table if exists " + DdlReference.IDEMPOTENT_RECORD_TABLE);
            }
        }
        try (Connection primary = open(PRIMARY_SCHEMA)) {
            ScriptUtils.executeSqlScript(primary, new EncodedResource(
                    new ClassPathResource(DdlReference.OUTBOX_DDL), StandardCharsets.UTF_8));
            ScriptUtils.executeSqlScript(primary, new EncodedResource(
                    new ClassPathResource(DdlReference.IDEMPOTENT_RECORD_DDL), StandardCharsets.UTF_8));
        }
    }

    private static Connection open(String schema) throws SQLException {
        return DriverManager.getConnection(urlFor(schema), MydddMysqlContainer.MYSQL.getUsername(),
                MydddMysqlContainer.MYSQL.getPassword());
    }

    @Test
    void should_default_to_primary_and_switch_the_session_to_report() throws SQLException {
        assertThat(jdbc.queryForObject("select schema()", String.class)).isEqualTo(PRIMARY_SCHEMA);
        assertThat(reportProbe.currentSchema()).isEqualTo(REPORT_SCHEMA);
        // 切库调用返回后必须回到默认库：路由键靠 ThreadLocal 栈式恢复，泄漏会让后续写入落错库
        try (Connection connection = dataSource.getConnection()) {
            assertThat(jdbc.queryForObject("select schema()", String.class)).isEqualTo(PRIMARY_SCHEMA);
        }
    }

    @Test
    void should_show_the_annotated_write_only_in_the_second_schema() {
        jdbc.update("insert into " + PROBE_TABLE + " (note) values ('written-by-default')");
        reportProbe.write("written-by-report");

        assertThat(count(PRIMARY_SCHEMA)).isEqualTo(1);
        assertThat(count(REPORT_SCHEMA)).isEqualTo(1);
        try (Connection primary = open(PRIMARY_SCHEMA); java.sql.ResultSet rs = primary.createStatement()
                .executeQuery("select note from " + PROBE_TABLE)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("written-by-default");
        } catch (SQLException failed) {
            throw new AssertionError(failed);
        }
    }

    @Test
    void should_build_both_reference_ddls_on_real_mysql_with_their_own_indexes() {
        List<String> outboxIndexes = jdbc.queryForList("select distinct index_name from information_schema.statistics"
                + " where table_schema = ? and table_name = ?", String.class, PRIMARY_SCHEMA,
                DdlReference.OUTBOX_TABLE);
        assertThat(outboxIndexes).contains("PRIMARY", "idx_myddd_outbox_dispatchable", "idx_myddd_outbox_lease");

        List<String> idempotentIndexes = jdbc.queryForList("select distinct index_name from information_schema"
                + ".statistics where table_schema = ? and table_name = ?", String.class, PRIMARY_SCHEMA,
                DdlReference.IDEMPOTENT_RECORD_TABLE);
        assertThat(idempotentIndexes).contains("PRIMARY");

        Integer tables = jdbc.queryForObject("select count(*) from information_schema.tables where table_schema = ?"
                + " and table_name in (?, ?)", Integer.class, PRIMARY_SCHEMA, DdlReference.OUTBOX_TABLE,
                DdlReference.IDEMPOTENT_RECORD_TABLE);
        assertThat(tables).as("V1 与 V2 都必须在 MySQL 上执行成功").isEqualTo(2);
    }

    private int count(String schema) {
        try (Connection connection = open(schema); java.sql.ResultSet rs = connection.createStatement()
                .executeQuery("select count(*) from " + PROBE_TABLE)) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException failed) {
            throw new AssertionError(failed);
        }
    }

    /** 夹具：只服务本类的归属证明，不进交付物。 */
    static class ReportProbe {

        private final JdbcTemplate jdbc;

        ReportProbe(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @UseDataSource("report")
        public String currentSchema() {
            return jdbc.queryForObject("select schema()", String.class);
        }

        @UseDataSource("report")
        public void write(String note) {
            jdbc.update("insert into " + PROBE_TABLE + " (note) values (?)", note);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RoutingFixture {

        @Bean
        ReportProbe reportProbe(JdbcTemplate jdbcTemplate) {
            return new ReportProbe(jdbcTemplate);
        }
    }
}
