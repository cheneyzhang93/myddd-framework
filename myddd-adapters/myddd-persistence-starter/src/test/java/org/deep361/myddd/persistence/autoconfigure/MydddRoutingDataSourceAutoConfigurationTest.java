package org.deep361.myddd.persistence.autoconfigure;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.persistence.routing.AspectjProxyGuard;
import org.deep361.myddd.persistence.routing.DataSourceRoutingAspect;
import org.deep361.myddd.persistence.routing.RoutingDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由模式装配面的语义。
 *
 * <p>{@link AopAutoConfiguration} 出现在每个用例里：装配 {@link DataSourceRoutingAspect} 的前提
 * （AspectJ 感知的代理创建器存在）由 {@code AspectjProxyGuard} 负责，本模块的装配必须与真实的
 * Boot 上下文形状一致，而不是绕过它。路由的目标库用 h2 内存串——建 {@code DataSource} 不建连接，
 * 所以这一层不需要 Docker（可达性在 Task 20）。
 */
class MydddRoutingDataSourceAutoConfigurationTest {

    private static final String PRIMARY_URL = "jdbc:h2:mem:cfg-primary";
    private static final String REPORT_URL = "jdbc:h2:mem:cfg-report";
    // withPropertyValues 是 String... 变参，每条只按第一个 '=' 切：把三对拼成一个逗号串不会拆开，
    // 而是让 primary.url 的值一路吞到行尾、另两对根本不存在（实测报错 "datasources.report.url 不能为空"）。
    private static final String PRIMARY_DATASOURCE = "myddd.persistence.datasources.primary.url=" + PRIMARY_URL;
    private static final String REPORT_DATASOURCE = "myddd.persistence.datasources.report.url=" + REPORT_URL;
    private static final String DEFAULT_IS_REPORT = "myddd.persistence.default-datasource=report";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class,
                    MydddRoutingDataSourceAutoConfiguration.class));

    @Test
    void should_not_route_when_no_named_datasource_is_configured() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(RoutingDataSource.class);
            assertThat(context).doesNotHaveBean(DataSourceRoutingAspect.class);
        });
    }

    @Test
    void should_not_route_when_the_persistence_capability_is_disabled() {
        runner.withPropertyValues(PRIMARY_DATASOURCE, REPORT_DATASOURCE, DEFAULT_IS_REPORT,
                "myddd.persistence.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(RoutingDataSource.class);
            assertThat(context).doesNotHaveBean(DataSourceRoutingAspect.class);
        });
    }

    @Test
    void should_route_when_a_single_named_datasource_is_defined() {
        runner.withPropertyValues("myddd.persistence.datasources.primary.url=" + PRIMARY_URL).run(context -> {
            RoutingDataSource router = context.getBean(RoutingDataSource.class);

            assertThat(router.getKeys()).containsExactly("primary");
            assertThat(router.getDefaultKey()).isEqualTo("primary");
            // 路由一旦启用，启动自检的守卫 bean 必须同时在位：M16（删掉 mydddAspectjProxyGuard 这个 @Bean）
            // 只能被这一条抓到——其余用例都带 AopAutoConfiguration，守卫不会报警，只有"它在不在容器里"能钉住装配。
            assertThat(context).hasSingleBean(AspectjProxyGuard.class);
        });
    }

    @Test
    void should_build_one_pooled_datasource_per_key_and_keep_the_default_key() {
        runner.withPropertyValues(PRIMARY_DATASOURCE, REPORT_DATASOURCE, DEFAULT_IS_REPORT,
                "myddd.persistence.datasources.report.username=reader",
                "myddd.persistence.datasources.report.password=secret",
                "myddd.persistence.datasources.report.driver-class-name=org.h2.Driver").run(context -> {
            RoutingDataSource router = context.getBean(RoutingDataSource.class);

            assertThat(router.getKeys()).containsExactly("primary", "report");
            assertThat(router.getDefaultKey()).isEqualTo("report");

            HikariDataSource report = (HikariDataSource) router.getResolvedDataSources().get("report");
            assertThat(report.getJdbcUrl()).isEqualTo(REPORT_URL);
            assertThat(report.getUsername()).isEqualTo("reader");
            assertThat(report.getDriverClassName()).isEqualTo("org.h2.Driver");

            HikariDataSource primary = (HikariDataSource) router.getResolvedDataSources().get("primary");
            assertThat(primary.getJdbcUrl()).isEqualTo(PRIMARY_URL);
            assertThat(primary.getUsername()).isNull();
        });
    }

    @Test
    void should_fail_fast_when_spring_datasource_url_is_configured_as_well() {
        runner.withPropertyValues(PRIMARY_DATASOURCE, REPORT_DATASOURCE, DEFAULT_IS_REPORT,
                "spring.datasource.url=jdbc:h2:mem:conflict")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(FrameworkException.class)
                            .hasMessageContaining("spring.datasource.url")
                            .hasMessageContaining("myddd.persistence.datasources");
                });
    }

    @Test
    void should_keep_boot_from_creating_a_second_datasource_when_routing() {
        runner.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withPropertyValues(PRIMARY_DATASOURCE, REPORT_DATASOURCE, DEFAULT_IS_REPORT)
                .run(context -> {
                    assertThat(context.getBeanNamesForType(DataSource.class)).containsExactly("mydddRoutingDataSource");
                    assertThat(context.getBean(DataSource.class)).isInstanceOf(RoutingDataSource.class);
                });
    }

    @Test
    void should_stay_the_primary_data_source_when_the_application_declares_another_one() {
        runner.withPropertyValues(PRIMARY_DATASOURCE, REPORT_DATASOURCE, DEFAULT_IS_REPORT)
                .withUserConfiguration(LegacyDataSourceConfiguration.class)
                .run(context -> {
                    assertThat(context.getBeanNamesForType(DataSource.class))
                            .containsExactlyInAnyOrder("mydddRoutingDataSource", "legacyDataSource");
                    assertThat(context.getBean(DataSource.class)).isInstanceOf(RoutingDataSource.class);
                });
    }

    @Test
    void should_declare_itself_before_the_auto_configurations_that_must_see_the_routing_bean() {
        AutoConfiguration annotation = MydddRoutingDataSourceAutoConfiguration.class.getAnnotation(AutoConfiguration.class);

        assertThat(annotation).isNotNull();
        assertThat(Arrays.stream(annotation.before()).map(Class::getName))
                .containsExactlyInAnyOrder(DataSourceAutoConfiguration.class.getName(),
                        MybatisPlusAutoConfiguration.class.getName());
    }

    @Test
    void should_still_gate_mybatis_plus_on_the_single_candidate_contract() {
        ConditionalOnSingleCandidate condition =
                MybatisPlusAutoConfiguration.class.getAnnotation(ConditionalOnSingleCandidate.class);

        assertThat(condition).isNotNull();
        assertThat(condition.value()).isEqualTo(DataSource.class);
    }

    @Configuration(proxyBeanMethods = false)
    static class LegacyDataSourceConfiguration {

        @Bean
        DataSource legacyDataSource() {
            return DataSourceBuilder.create().url("jdbc:h2:mem:legacy").build();
        }
    }
}
