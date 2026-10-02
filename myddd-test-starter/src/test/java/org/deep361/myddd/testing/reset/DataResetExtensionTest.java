package org.deep361.myddd.testing.reset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.InOrder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 扩展的四条行为：默认开启、开关生效、注解排除与配置键排除合并、把上下文数据源交给执行体。
 */
class DataResetExtensionTest {

    @DataReset(exclude = "seed_table")
    static class AnnotatedSample {
    }

    static class UnannotatedSample {
    }

    @Test
    void should_not_touch_the_datasource_when_disabled() {
        DataSource dataSource = mock(DataSource.class);
        DataResetExtension extension = extensionResolving(dataSource,
                Map.of(DataResetExtension.ENABLED_PROPERTY, false));

        extension.beforeEach(contextFor(AnnotatedSample.class));

        verifyNoInteractions(dataSource);
    }

    @Test
    void should_reset_by_default_with_the_merged_exclusions() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        givenTables(dataSource, connection, statement, "orders", "seed_table", "dict_city", "flyway_schema_history");
        DataResetExtension extension = extensionResolving(dataSource,
                Map.of(DataResetExtension.EXCLUDE_PROPERTY, "dict_city"));

        extension.beforeEach(contextFor(AnnotatedSample.class));

        InOrder inOrder = inOrder(statement);
        inOrder.verify(statement).execute("SET FOREIGN_KEY_CHECKS = 0");
        inOrder.verify(statement).execute("TRUNCATE TABLE `orders`");
        inOrder.verify(statement).execute("SET FOREIGN_KEY_CHECKS = 1");
        verify(statement, never()).execute(truncating("seed_table"));
        verify(statement, never()).execute(truncating("dict_city"));
        verify(statement, never()).execute(truncating("flyway_schema_history"));
    }

    @Test
    void should_merge_annotation_exclusions_before_the_global_ones() {
        StandardEnvironment environment = environment(Map.of(DataResetExtension.EXCLUDE_PROPERTY,
                "dict_city,audit_log"));

        assertThat(DataResetExtension.collectExcluded(AnnotatedSample.class, environment))
                .containsExactly("seed_table", "dict_city", "audit_log");
        assertThat(DataResetExtension.collectExcluded(UnannotatedSample.class, environment(Map.of())))
                .isEmpty();
    }

    @Test
    void should_default_the_switch_to_enabled() {
        assertThat(environment(Map.of()).getProperty(DataResetExtension.ENABLED_PROPERTY, Boolean.class, true))
                .isTrue();
    }

    private static String truncating(String table) {
        return "TRUNCATE TABLE `" + table + "`";
    }

    /** 用 resolveContext 这条测试缝把上下文换成手工构造的 GenericApplicationContext，不启 Spring Boot。 */
    private static DataResetExtension extensionResolving(DataSource dataSource, Map<String, Object> properties) {
        GenericApplicationContext context = new GenericApplicationContext();
        context.getBeanFactory().registerSingleton("dataSource", dataSource);
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("mydddDataResetTest", properties));
        context.refresh();
        ApplicationContext resolved = context;
        return new DataResetExtension() {
            @Override
            protected ApplicationContext resolveContext(ExtensionContext ignored) {
                return resolved;
            }
        };
    }

    private static ExtensionContext contextFor(Class<?> testClass) {
        ExtensionContext context = mock(ExtensionContext.class);
        // getRequiredTestClass() 是 <S> Class<S> 泛型方法，thenReturn(Class<?>) 推断不出捕获类型，
        // 改用 doReturn 绕开（实测编译错误："对于thenReturn(java.lang.Class<capture#1, 共 ?>) 找不到合适的方法"）
        doReturn(testClass).when(context).getRequiredTestClass();
        return context;
    }

    private static StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
    }

    private static void givenTables(DataSource dataSource, Connection connection, Statement statement,
            String... names) throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        AtomicInteger cursor = new AtomicInteger();
        when(resultSet.next()).thenAnswer(ignored -> cursor.get() < names.length);
        when(resultSet.getString(1)).thenAnswer(ignored -> names[cursor.getAndIncrement()]);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
    }
}
