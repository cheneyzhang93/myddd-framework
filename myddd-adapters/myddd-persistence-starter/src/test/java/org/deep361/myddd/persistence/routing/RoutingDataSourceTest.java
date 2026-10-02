package org.deep361.myddd.persistence.routing;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 路由器怎么按线程绑定的键派发连接。
 *
 * <p>取证方式是读连接自身的元数据（{@code getMetaData().getURL()}）：两个 h2 内存库各自报出自己的库名，
 * 这比"我们确实调了 {@code map.get(key)}"强——mock 出来的 {@code DataSource} 只能证明调用发生，
 * 证不了归属。实测 h2 会把 URL 参数剥掉，所以断言可以写全串。
 */
class RoutingDataSourceTest {

    private static final String PRIMARY_URL = "jdbc:h2:mem:myddd-primary";
    private static final String REPORT_URL = "jdbc:h2:mem:myddd-report";

    private RoutingDataSource router;

    private static DataSource pool(String name) {
        return DataSourceBuilder.create().url("jdbc:h2:mem:myddd-" + name + ";DB_CLOSE_DELAY=-1").build();
    }

    private static Map<String, DataSource> targets() {
        Map<String, DataSource> targets = new LinkedHashMap<>();
        targets.put("primary", pool("primary"));
        targets.put("report", pool("report"));
        return targets;
    }

    @BeforeEach
    void buildRouter() {
        router = new RoutingDataSource(targets(), "primary");
        router.afterPropertiesSet();
    }

    @AfterEach
    void unbindAndClose() throws Exception {
        DataSourceKeyHolder.restore(null);
        router.destroy();
    }

    private String routedUrl() throws SQLException {
        try (Connection connection = router.getConnection()) {
            return connection.getMetaData().getURL();
        }
    }

    @Test
    void should_use_the_default_target_when_no_key_is_bound() throws SQLException {
        assertThat(DataSourceKeyHolder.current()).isNull();
        assertThat(router.getDefaultKey()).isEqualTo("primary");
        assertThat(router.getKeys()).containsExactly("primary", "report");

        assertThat(routedUrl()).isEqualTo(PRIMARY_URL);
    }

    @Test
    void should_route_to_the_target_bound_on_the_current_thread() throws SQLException {
        DataSourceKeyHolder.bind("report");

        assertThat(routedUrl()).isEqualTo(REPORT_URL);
    }

    @Test
    void should_switch_targets_when_the_bound_key_changes() throws SQLException {
        DataSourceKeyHolder.runWith("report", () -> {
            try {
                assertThat(routedUrl()).isEqualTo(REPORT_URL);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });

        assertThat(routedUrl()).isEqualTo(PRIMARY_URL);

        DataSourceKeyHolder.bind("report");
        assertThat(routedUrl()).isEqualTo(REPORT_URL);
        DataSourceKeyHolder.restore(null);
        assertThat(routedUrl()).isEqualTo(PRIMARY_URL);
    }

    @Test
    void should_fail_fast_on_any_key_that_is_not_defined() {
        DataSourceKeyHolder.bind("nope");

        assertThatThrownBy(this::routedUrl)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("[nope]")
                .hasMessageContaining("myddd.persistence.datasources")
                .hasMessageContaining("primary")
                .hasMessageContaining("report");

        assertThatThrownBy(() -> new RoutingDataSource(targets(), "missing"))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("missing");

        assertThatThrownBy(() -> new RoutingDataSource(Map.of(), "primary"))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("至少");

        DataSourceKeyHolder.restore(null);
    }

    @Test
    void should_close_every_target_pool_when_the_router_is_destroyed() {
        Map<Object, DataSource> resolved = router.getResolvedDataSources();
        assertThat(resolved).hasSize(2);
        assertThat(((HikariDataSource) resolved.get("primary")).isClosed()).isFalse();

        router.destroy();

        assertThat(((HikariDataSource) resolved.get("primary")).isClosed()).isTrue();
        assertThat(((HikariDataSource) resolved.get("report")).isClosed()).isTrue();
    }

    @Test
    void should_report_which_target_failed_to_close() {
        Map<String, DataSource> targets = new LinkedHashMap<>();
        HikariDataSource healthy = (HikariDataSource) pool("primary");
        targets.put("primary", healthy);
        targets.put("stubborn", stubbornPool());
        RoutingDataSource closing = new RoutingDataSource(targets, "primary");
        closing.afterPropertiesSet();

        assertThatThrownBy(closing::destroy)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("关闭数据源连接池失败")
                .hasMessageContaining("stubborn")
                .hasMessageContaining("后台线程还在用这个池");

        // 一个池关不掉，不能顺带把另一个池漏掉：两个池都是框架建的，都必须被尝试关闭。
        assertThat(healthy.isClosed()).isTrue();
    }

    /** 一个"关不掉"的连接池：真实故障是 Hikari 关闭时后台线程报错，这里用代理复现同样的抛错形态。 */
    private static DataSource stubbornPool() {
        return (DataSource) Proxy.newProxyInstance(RoutingDataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class, AutoCloseable.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        throw new SQLException("后台线程还在用这个池");
                    }
                    return "isWrapperFor".equals(method.getName()) ? Boolean.FALSE : null;
                });
    }
}
