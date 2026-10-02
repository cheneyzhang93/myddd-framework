package org.deep361.myddd.persistence.outbox;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Locale;

import org.springframework.jdbc.core.PreparedStatementCreator;

/**
 * 分页"跳过 N 行取 M 行"的 SQL 子句方言表，连同它的参数绑定顺序。
 *
 * <p><b>为什么需要这张表</b>：SQL 里"限制行数"没有唯一写法。MySQL 与 MariaDB 只认 {@code limit ? offset ?}；
 * PostgreSQL、Oracle 12c+、DB2、SQL Server 2012+、H2 认的是标准形态 {@code offset ? rows fetch next ? rows only}。
 * 硬编码任意一种都会让这套存储只在一个数据库上可用，而"同一套代码换库能跑"是本框架的设计目标。
 *
 * <p><b>子句与绑定同住在这里</b>：两种形态的占位符顺序相反——MySQL 先给行数再给跳过数，标准形态反过来。
 * 文本写在 SQL 里、顺序写在调用方，就等着"换了方言忘了换绑定"：SQL 合法、参数齐，只是把页大小当成了偏移量，
 * 症状是翻页翻出重复或空页。
 *
 * <p><b>标准形态是默认，MySQL 系是名单里的例外</b>：只按 {@link java.sql.DatabaseMetaData#getDatabaseProductName()}
 * 判定，命中 mysql/maria 才用非标准形态，认不出的产品一律走标准形态——认不出时用标准形态，接主流库直接能跑；
 * 用 MySQL 形态则会在 PostgreSQL 上语法错，报错位置离真正原因很远。
 *
 * <p><b>方言在拿到连接的那一刻才解析</b>（见 {@link #paged}）：构造期取连接会让应用启动依赖数据库可用性，
 * 而数据库临时不可达时本该只是这一次查询失败，不该顺带把整个应用带走；在 {@code prepareStatement} 之前用
 * <b>这条语句自己的连接</b>问元数据，则既不额外要连接（一次翻页仍然只占一条连接），也在路由数据源下
 * 拿到了真正执行它的那个库的产品名。
 */
final class PagingClause {

    /** 子句文本与参数顺序成对定义，不允许单独改一边。 */
    private enum Form {

        MYSQL("limit ? offset ?") {
            @Override
            void bind(PreparedStatement ps, int start, long rowCount, long offset) throws SQLException {
                ps.setLong(start, rowCount);
                ps.setLong(start + 1, offset);
            }
        },

        ANSI("offset ? rows fetch next ? rows only") {
            @Override
            void bind(PreparedStatement ps, int start, long rowCount, long offset) throws SQLException {
                ps.setLong(start, offset);
                ps.setLong(start + 1, rowCount);
            }
        };

        private final String clause;

        Form(String clause) {
            this.clause = clause;
        }

        abstract void bind(PreparedStatement ps, int start, long rowCount, long offset) throws SQLException;
    }

    private PagingClause() {
    }

    /**
     * 拼出一条带分页子句的 {@link PreparedStatementCreator}：SQL 前缀（到 {@code order by ...} 为止）由调用方给，
     * 分页子句与它的两个参数在这里按当前连接的方言补上。
     *
     * @param selectWithoutPaging 不含行限制子句的 {@code select}，末尾不要留空格
     * @param bindPrefix          绑定子句之前的参数，入参是 1 基起始下标、返回下一个可用下标
     * @param rowCount            本页取多少行（已过 {@code query-limit} 上限）
     * @param offset              跳过多少行
     */
    static PreparedStatementCreator paged(String selectWithoutPaging, PrefixBinder bindPrefix, long rowCount,
            long offset) {
        return connection -> {
            Form form = formFor(connection.getMetaData().getDatabaseProductName());
            PreparedStatement ps = connection.prepareStatement(selectWithoutPaging + " " + form.clause);
            form.bind(ps, bindPrefix.bind(ps, 1), rowCount, offset);
            return ps;
        };
    }

    private static Form formFor(String databaseProductName) {
        String product = databaseProductName == null ? "" : databaseProductName.toLowerCase(Locale.ROOT);
        return product.contains("mysql") || product.contains("maria") ? Form.MYSQL : Form.ANSI;
    }

    /** 绑定分页子句之前的那串参数：入参是 1 基起始下标，返回下一个可用下标。 */
    @FunctionalInterface
    interface PrefixBinder {
        int bind(PreparedStatement ps, int start) throws SQLException;
    }
}
