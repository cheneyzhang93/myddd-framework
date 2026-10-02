package org.deep361.myddd.persistence.outbox;

import org.deep361.myddd.domain.outbox.OutboxRecord;
import org.deep361.myddd.domain.outbox.OutboxStatus;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.persistence.ddl.DdlReference;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * {@code myddd_outbox} 这一张表的 SQL 公共件：表名守卫、首跑建表提示、语句绑定与行→record 映射。
 *
 * <p><b>为什么要抽出来</b>：写侧（{@link JdbcOutboxStore}）与读侧（{@link JdbcOutboxDeadLetterStore}）
 * 操作同一张表、同一组列，三处逻辑必须只有一份真源：
 * <ol>
 *   <li><b>表名形状</b>——它是唯一会原样拼进 SQL 文本的用户输入。两个入口各写一遍正则，就意味着
 *       改一处漏一处，而漏掉那一个的注入面正好在被漏的那半边。</li>
 *   <li><b>行映射</b>——{@code OutboxRecord} 的 13 个组件与列名一一对应，两份映射表会漂移
 *       （典型症状：写侧带 {@code last_error}，读侧忘了带，运维查死信看不到失败原因）。</li>
 *   <li><b>首跑建表提示</b>——"表还没建"在读写两侧的表现完全一样，提示文案也必须一样。</li>
 * </ol>
 *
 * <p>包私有：这不是给业务用的扩展点，业务要换实现请实现 {@link OutboxStore}（写侧）或
 * {@link OutboxDeadLetterStore}（读侧），而不是绕开这里的守卫。
 *
 * <p>{@link #execute(String, Supplier)} 里的 {@code statementProven} 只服务于一件事：区分
 * "第一次执行就语法错（多半是表没建）"与"跑起来之后才语法错（是我们的 SQL 错了）"，前者换成建表提示、
 * 后者原样上抛。它不参与任何业务判定，因此并发下偶尔多换一次或早换一次都不影响正确性。
 */
final class OutboxSql {

    /** 表名的合法形状，与 {@code myddd.persistence.outbox.table} 的约定同一条。 */
    static final String TABLE_NAME_RULE = "[a-z0-9_]{1,64}";
    private static final Pattern TABLE_NAME = Pattern.compile(TABLE_NAME_RULE);

    private final JdbcTemplate jdbc;
    private final String table;

    /** 是否已经成功执行过至少一条语句——只决定语法错误要不要配建表提示，不参与任何业务判定。 */
    private volatile boolean statementProven;

    OutboxSql(DataSource dataSource, String table) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "Outbox 表的数据源不能为 null"));
        this.table = requireTableName(table);
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    String table() {
        return table;
    }

    /** 表名单一校验点：构造期失败，绝不把未经检查的字符串拼进 SQL。 */
    static String requireTableName(String table) {
        Objects.requireNonNull(table, "Outbox 表名不能为 null");
        if (!TABLE_NAME.matcher(table).matches()) {
            throw new FrameworkException("Outbox 表名 \"" + table + "\" 非法：只允许 " + TABLE_NAME_RULE
                    + "。这个取值会原样拼进 SQL 文本，框架不接受引号、空白或分号形态；"
                    + "请检查 myddd.persistence.outbox.table（默认 " + DdlReference.OUTBOX_TABLE
                    + "），建表脚本见 classpath:" + DdlReference.OUTBOX_DDL);
        }
        return table;
    }

    /**
     * 包一层语法错误的处理：首次执行就报语法/对象错误时给出建表提示，之后原样上抛。
     */
    <T> T execute(String task, Supplier<T> action) {
        try {
            T result = action.get();
            this.statementProven = true;
            return result;
        } catch (BadSqlGrammarException failure) {
            if (statementProven) {
                throw failure;
            }
            throw new FrameworkException(task + " 失败：SQL 语法或对象不存在（表 " + table + "）。首次执行就报这个错，"
                    + "最常见的原因是表还没建或 myddd.persistence.outbox.table 指错了表名——参考建表脚本在 classpath:"
                    + DdlReference.OUTBOX_DDL + "，按同目录 README.md 复制进你自己的迁移序列（框架不会自动注入它）。"
                    + "原始错误：" + failure.getMessage(), failure);
        }
    }

    /** 列名清单拼成 {@code select}/{@code insert} 用的列表；真源是 record 组件，见 {@link DdlReference#outboxRecordColumns()}。 */
    static String columnList() {
        return String.join(", ", DdlReference.outboxRecordColumns());
    }

    /** 占位符串：{@code columns} 个 {@code ?}，逗号分隔。 */
    static String placeholders(int columns) {
        return String.join(", ", java.util.Collections.nCopies(columns, "?"));
    }

    static PreparedStatementCreator creator(String sql, StatementBinder bind) {
        return connection -> {
            PreparedStatement ps = connection.prepareStatement(sql);
            bind.bind(ps);
            return ps;
        };
    }

    /**
     * 带行数上限的语句工厂：上限走 JDBC 标准的 {@link PreparedStatement#setMaxRows(int)}，
     * <b>不往 SQL 文本里塞行限制子句</b>——{@code limit}/{@code fetch first}/{@code rownum} 是四家四种写法，
     * 写死任何一种都会让这个存储只在某一个数据库上可用。
     */
    static PreparedStatementCreator limited(String sql, int maxRows, StatementBinder bind) {
        return connection -> {
            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setMaxRows(maxRows);
            bind.bind(ps);
            return ps;
        };
    }

    static OutboxRecord toRecord(ResultSet rs) throws SQLException {
        return new OutboxRecord(rs.getString("event_id"), rs.getString("event_type"), rs.getString("aggregate_type"),
                rs.getString("aggregate_id"), rs.getString("trace_id"), toInstant(rs, "occurred_at"),
                rs.getString("channel"), rs.getString("payload"), OutboxStatus.valueOf(rs.getString("status")),
                rs.getInt("retry_count"), toInstant(rs, "next_retry_at"), rs.getString("last_error"),
                toInstant(rs, "created_at"));
    }

    static Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** 函数式接口：把参数绑进已经带好 SQL 的 {@link PreparedStatement}。 */
    @FunctionalInterface
    interface StatementBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }
}
