package org.deep361.myddd.persistence.routing;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.Ordered;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 路由与事务的配合：设计要点 2 与 3 的证据都在这里。
 *
 * <p>断言口径是"事务里那条连接属于哪个库"（读 {@code Connection.getMetaData().getURL()}），这是唯一能
 * 区分"切面在事务外"与"切面在事务内"的观测量：后者看起来一切正常（注解确实被读了），但写进了默认库。
 */
class RoutingDataSourceTransactionTest {

    private static final String PRIMARY_URL = "jdbc:h2:mem:myddd-primary";
    private static final String REPORT_URL = "jdbc:h2:mem:myddd-report";

    static class RoutingProbe {

        private final DataSource dataSource;

        RoutingProbe(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Transactional
        public String urlInsideTransaction() throws SQLException {
            return connectedUrl();
        }

        @Transactional
        @UseDataSource("report")
        public String annotatedUrlInsideTransaction() throws SQLException {
            return connectedUrl();
        }

        private String connectedUrl() throws SQLException {
            return DataSourceUtils.getConnection(dataSource).getMetaData().getURL();
        }
    }
    private static DataSource pool(String name) {
        return DataSourceBuilder.create().url("jdbc:h2:mem:myddd-" + name + ";DB_CLOSE_DELAY=-1").build();
    }

    private static DataSource routingDataSource() {
        Map<String, DataSource> targets = new LinkedHashMap<>();
        targets.put("primary", pool("primary"));
        targets.put("report", pool("report"));
        RoutingDataSource router = new RoutingDataSource(targets, "primary");
        router.afterPropertiesSet();
        return router;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Routed {

        @Bean
        DataSource dataSource() {
            return routingDataSource();
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        DataSourceRoutingAspect mydddDataSourceAspect() {
            return new DataSourceRoutingAspect();
        }

        @Bean
        AspectjProxyGuard mydddAspectjProxyGuard(ConfigurableListableBeanFactory beanFactory) {
            return new AspectjProxyGuard(beanFactory);
        }

        @Bean
        RoutingProbe probe(DataSource dataSource) {
            return new RoutingProbe(dataSource);
        }
    }

    /** 与 {@link Routed} 逐 bean 相同，只是少了 {@code @EnableAspectJAutoProxy}：即 {@code spring.aop.auto=false} 的容器形状。 */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionOnly {

        @Bean
        DataSource dataSource() {
            return routingDataSource();
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        DataSourceRoutingAspect mydddDataSourceAspect() {
            return new DataSourceRoutingAspect();
        }

        @Bean
        AspectjProxyGuard mydddAspectjProxyGuard(ConfigurableListableBeanFactory beanFactory) {
            return new AspectjProxyGuard(beanFactory);
        }

        @Bean
        RoutingProbe probe(DataSource dataSource) {
            return new RoutingProbe(dataSource);
        }
    }

    @Test
    void should_open_the_transaction_on_the_target_selected_by_the_annotation() throws SQLException {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Routed.class)) {
            assertThat(context.getBean(RoutingProbe.class).annotatedUrlInsideTransaction()).isEqualTo(REPORT_URL);
            assertThat(DataSourceKeyHolder.current()).isNull();
        }
    }

    @Test
    void should_open_the_transaction_on_the_default_target_when_nothing_is_bound() throws SQLException {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Routed.class)) {
            assertThat(context.getBean(RoutingProbe.class).urlInsideTransaction()).isEqualTo(PRIMARY_URL);
        }
    }

    @Test
    void should_be_ordered_strictly_before_the_transaction_advisor() {
        assertThat(DataSourceRoutingAspect.ASPECT_ORDER).isLessThan(Ordered.LOWEST_PRECEDENCE);

        // 前提一：事务通知器的顺序取自事务管理器，而 DataSourceTransactionManager 不实现 Ordered
        //         ⇒ 它是 LOWEST_PRECEDENCE，所以"严格小于"才是有效约束。
        assertThat(Ordered.class.isAssignableFrom(DataSourceTransactionManager.class)).isFalse();

        // 前提二：本框架的顺序判定依赖容器里确实存在 AspectJ 感知的代理创建器。
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Routed.class)) {
            assertThat(context.getBeanNamesForType(AnnotationAwareAspectJAutoProxyCreator.class)).hasSize(1);
        }
    }

    @Test
    void should_fail_fast_when_the_aspect_would_never_be_applied() {
        assertThatThrownBy(() -> new AnnotationConfigApplicationContext(TransactionOnly.class))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining(AnnotationAwareAspectJAutoProxyCreator.class.getSimpleName());
    }
}
