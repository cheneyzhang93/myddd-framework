package org.deep361.myddd.testing.container;

import org.deep361.myddd.testing.image.DevboxImages;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * devbox 同源 XXL-Job admin（3.4.2）+ 它专属的 MySQL，供 {@code JobPort} 的 xxljob 适配器集成测试使用。
 *
 * <p>Testcontainers 2.0.5 没有 xxl-job 模块，且 admin 自己就要一个 MySQL，所以这里成对持有：admin 与 MySQL
 * 走同一张 {@link Network}（容器间用别名 {@code mysql} 互访），宿主侧只暴露 admin 的随机映射端口——
 * 执行器在宿主 JVM 里跑，IT 的判据全部落在"宿主 → admin → 注册表"这条<b>向上写</b>的方向上。
 * <b>"admin → 执行器"的回调方向不在断言面上</b>：{@code address} 由厂商在 {@code startEmbedServer} 里按模板
 * {@code "http://{ip_port}/"} 自动合成，执行器接缝只递 {@code adminAddresses/appname/accessToken/port}
 * 四个配置，刻意不碰 {@code ip}/{@code address}。
 *
 * <p>admin 3.4.2 镜像的一手实况：{@code server.servlet.context-path=/}（URL 不拼 {@code /xxl-job-admin}），
 * {@code xxl.job.accessToken} 缺省是 {@code default_token}（<b>非空</b>，所以必须由 {@code PARAMS} 显式覆盖，
 * 否则空口令的执行器注册会被拒），唯一配置入口是环境变量 {@code PARAMS}（复数）——写成 {@code PARAM}
 * 会被静默忽略（镜像的 entrypoint 只展开 {@code $PARAMS}）。
 */
public final class MydddXxlJobAdminContainer implements AutoCloseable {

    /** IT 与执行器约定的口令（镜像缺省是 {@code default_token}，这里覆盖成显式值以证明覆盖生效）。 */
    public static final String ACCESS_TOKEN = "myddd-it-token";
    /** IT 执行器的 appname：注册断言按它查 {@code xxl_job_registry.registry_key}。 */
    public static final String EXECUTOR_APP_NAME = "myddd-it-executor";

    private static final int ADMIN_PORT = 8080;
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);

    private final Network network = Network.newNetwork();
    private final MySQLContainer mysql;
    private final GenericContainer<?> admin;

    public MydddXxlJobAdminContainer() {
        // 2.0.5 里 org.testcontainers.containers.MySQLContainer 是类级 @Deprecated 的泛型版，
        // 现行替身 org.testcontainers.mysql.MySQLContainer **非泛型**（javap 实测：链式方法
        // withDatabaseName/withUsername/withPassword 直接返回 MySQLContainer 本身），
        // 所以字段只能写成 MySQLContainer，写成 MySQLContainer<?> 编译不过。
        mysql = new MySQLContainer(DevboxImages.dockerImage("mysql"))
                .withDatabaseName("xxl_job")
                .withUsername("root")
                .withPassword("myddd-it")
                .withInitScript("xxljob/tables_xxl_job.sql")
                .withNetwork(network)
                .withNetworkAliases("mysql");
        admin = new GenericContainer<>(DevboxImages.dockerImage("xxljob-admin"))
                .withNetwork(network)
                .withEnv("PARAMS", PARAMS_value())
                .withExposedPorts(ADMIN_PORT)
                .withStartupTimeout(STARTUP_TIMEOUT);
        // 顺序启动不是保守，是必需：初始化脚本（tables_xxl_job.sql）在 DB 容器自己的 start 过程里执行
        // （runInitScriptIfRequired 属 JdbcDatabaseContainer 的启动路径），而 admin 一起来就要连 xxl_job 库。
        // 并行启动（Startables.deepStart）时 admin 的 future 可能先完成，schema 还没建好
        // ⇒ 注册落库失败，IT 红在无关处。
        mysql.start();
        admin.start();
    }

    private static String PARAMS_value() {
        return "--spring.datasource.url=jdbc:mysql://mysql:3306/xxl_job"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + " --spring.datasource.username=root"
                + " --spring.datasource.password=myddd-it"
                + " --xxl.job.accessToken=" + ACCESS_TOKEN;
    }

    /** 执行器配置里 {@code adminAddresses} 该用的地址（context-path 是 {@code /}，所以不拼路径）。 */
    public String adminAddresses() {
        return "http://127.0.0.1:" + admin.getMappedPort(ADMIN_PORT);
    }

    /** 查注册表的 JDBC URL/账号/口令三元组：注册断言直接查 {@code xxl_job_registry}。 */
    public JdbcRegistry registry() {
        return new JdbcRegistry(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    @Override
    public void close() {
        admin.close();
        mysql.close();
        network.close();
    }

    /** admin 侧注册表的只读访问（把 {@code xxl_job_registry} 的三列形状收在这一个类型里，用例只写 SQL 语义）。 */
    public record JdbcRegistry(String jdbcUrl, String username, String password) {

        /** 该 appname 当前的注册条目（{@code registry_value} 列表）。执行器心跳每 30s 写一次。 */
        public List<String> registryValues(String registryGroup, String registryKey) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                 PreparedStatement statement = connection.prepareStatement(
                         "select registry_value from xxl_job_registry where registry_group = ? and registry_key = ?")) {
                statement.setString(1, registryGroup);
                statement.setString(2, registryKey);
                try (ResultSet rows = statement.executeQuery()) {
                    List<String> values = new ArrayList<>();
                    while (rows.next()) {
                        values.add(rows.getString(1));
                    }
                    return values;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("查询 xxl_job_registry 失败：" + registryGroup + "/" + registryKey, e);
            }
        }
    }
}
