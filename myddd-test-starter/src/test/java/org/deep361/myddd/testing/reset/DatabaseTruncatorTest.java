package org.deep361.myddd.testing.reset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DatabaseTruncator} 的 SQL 生成与取舍契约（Mockito 假 JDBC，不需要 Docker 也不需要真库）。
 */
class DatabaseTruncatorTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
    }

    /** 打桩"发现出这些表"：next() 与 getString(1) 共用同一个游标，次数天然对齐。 */
    private void givenTables(String... names) throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        AtomicInteger cursor = new AtomicInteger();
        when(resultSet.next()).thenAnswer(invocation -> cursor.get() < names.length);
        when(resultSet.getString(1)).thenAnswer(invocation -> names[cursor.getAndIncrement()]);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
    }

    @Test
    void should_truncate_every_found_table_and_report_them() throws SQLException {
        givenTables("orders", "order_item");
        DatabaseTruncator truncator = new DatabaseTruncator(dataSource, List.of());

        assertThat(truncator.reset()).containsExactly("orders", "order_item");
        InOrder inOrder = inOrder(statement);
        inOrder.verify(statement).execute("SET FOREIGN_KEY_CHECKS = 0");
        inOrder.verify(statement).execute("TRUNCATE TABLE `orders`");
        inOrder.verify(statement).execute("TRUNCATE TABLE `order_item`");
        inOrder.verify(statement).execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    @Test
    void should_never_truncate_the_flyway_history_table() throws SQLException {
        givenTables("flyway_schema_history", "orders");

        assertThat(new DatabaseTruncator(dataSource, List.of()).reset()).containsExactly("orders");
        verify(statement, never()).execute("TRUNCATE TABLE `flyway_schema_history`");
    }

    @Test
    void should_match_exclusions_case_insensitively() throws SQLException {
        givenTables("Audit_Log", "dict_city", "orders");

        assertThat(new DatabaseTruncator(dataSource, List.of("DICT_CITY", "audit_log", "  ")).reset())
                .containsExactly("orders");
    }

    @Test
    void should_find_only_base_tables_of_current_schema_in_stable_order() throws SQLException {
        givenTables("orders");
        new DatabaseTruncator(dataSource, List.of()).reset();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(statement).executeQuery(sql.capture());
        assertThat(sql.getValue())
                .containsIgnoringCase("from information_schema.tables")
                .containsIgnoringCase("table_schema = database()")
                .containsIgnoringCase("table_type = 'BASE TABLE'")
                .containsIgnoringCase("order by table_name");
    }

    @Test
    void should_restore_foreign_key_checks_and_report_progress_on_failure() throws SQLException {
        givenTables("orders", "order_item");
        when(statement.execute("TRUNCATE TABLE `order_item`")).thenThrow(new SQLException("boom"));
        DatabaseTruncator truncator = new DatabaseTruncator(dataSource, List.of());

        assertThatThrownBy(truncator::reset)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orders")
                .hasMessageContaining("数据源")
                .hasCauseInstanceOf(SQLException.class);
        verify(statement).execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    @Test
    void should_reject_illegal_table_names_before_touching_anything() throws SQLException {
        givenTables("orders; drop table x --");

        assertThatThrownBy(() -> new DatabaseTruncator(dataSource, List.of()).reset())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法表名");
        verify(statement, never()).execute(anyString());
    }

    @Test
    void should_return_the_connection_to_the_pool() throws SQLException {
        givenTables("orders");
        new DatabaseTruncator(dataSource, List.of()).reset();

        verify(connection).close();
    }

    @Test
    void should_issue_no_ddl_when_no_table_is_selected() throws SQLException {
        givenTables();
        assertThat(new DatabaseTruncator(dataSource, List.of()).reset()).isEmpty();
        verify(statement, never()).execute(anyString());
    }
}
