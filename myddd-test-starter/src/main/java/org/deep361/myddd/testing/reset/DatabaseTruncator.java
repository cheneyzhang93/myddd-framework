package org.deep361.myddd.testing.reset;

import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 清空当前 schema 的全部基表（MySQL 方言）。{@link DataResetExtension} 的执行体，也可被集成测试直接调用。
 *
 * <p>连接借还走 {@link DataSourceUtils}，因此会加入调用方的当前事务；一次 {@link #reset()} 只借一次连接，
 * 发现与清理共用它。
 */
public final class DatabaseTruncator {

    /** 只取当前库的基表并按表名排序：顺序稳定，SQL 与断言都可复现。 */
    private static final String TABLE_QUERY = """
            select table_name
            from information_schema.tables
            where table_schema = database()
              and table_type = 'BASE TABLE'
            order by table_name
            """;

    private final DataSource dataSource;
    private final TableNameList excluded;

    public DatabaseTruncator(DataSource dataSource, Collection<String> excluded) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource 不能为 null");
        this.excluded = TableNameList.of(excluded);
    }

    /**
     * 执行清理。
     *
     * @return 实际被 TRUNCATE 的表名（按发现顺序），便于集成测试断言"确实清过"
     * @throws IllegalArgumentException 发现的表名不在标识符白名单内（此时一条 DDL 都没发出）
     * @throws IllegalStateException    SQL 执行失败，消息里带已清空的表清单
     */
    public List<String> reset() {
        List<String> candidates = new ArrayList<>();
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            try (Statement statement = connection.createStatement()) {
                for (String table : discover(statement)) {
                    if (!excluded.shouldSkip(table)) {
                        candidates.add(table);
                    }
                }
                for (String table : candidates) {
                    TableNameList.requireSafeIdentifier(table);
                }
                if (candidates.isEmpty()) {
                    return List.of();
                }
                return truncate(statement, candidates);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("集成测试数据清理失败，已清空 " + candidates + "，数据源 " + dataSource
                    + "：" + e.getMessage(), e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private static List<String> discover(Statement statement) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery(TABLE_QUERY)) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    private static List<String> truncate(Statement statement, List<String> tables) throws SQLException {
        List<String> done = new ArrayList<>();
        statement.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            for (String table : tables) {
                statement.execute("TRUNCATE TABLE " + TableNameList.quoted(table));
                done.add(table);
            }
        } finally {
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
        return done;
    }
}
