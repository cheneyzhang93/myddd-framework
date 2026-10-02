package org.deep361.myddd.persistence.outbox;

import org.deep361.myddd.domain.outbox.OutboxRecord;
import org.deep361.myddd.domain.outbox.OutboxStatus;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.common.page.PageQuery;
import org.deep361.myddd.kernel.common.page.PageResult;
import org.deep361.myddd.persistence.ddl.DdlReference;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.transaction.support.TransactionOperations;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link OutboxDeadLetterStore} 的 JDBC 实现：{@code myddd_outbox} 上死信的分页查询与重放。
 *
 * <p><b>SQL 里没有任何用户输入</b>：表名走 {@link OutboxSql} 的形状守卫（与写侧同一条——两处各写一遍正则
 * 就是给自己留"改一处漏一处"的注入面），时间窗、页大小、offset、id 集合全部是绑定参数；
 * {@code in (?, ?, …)} 的占位符个数由集合大小决定，占位符本身是字面量。
 *
 * <p><b>时间窗条件按边界是否为 null 拼出来，而不是写成 {@code (? is null or created_at >= ?)}</b>：
 * 后一种在 MySQL 上是永真的通用式，优化器不会拿它去裁 {@code created_at} 的范围，等于放弃索引，
 * 而且它要把同一个值绑两次、更容易绑错列。宁可多两个分支，也不要一条不 sargable 的谓词。
 *
 * <p><b>为什么用一次事务把 count 与 select 包起来</b>：分开的两次自动提交读之间若正好有人也在处理死信，
 * 就会出现"第一页 5 条、总数 12、第二页漏一条"——在管理界面上表现为翻页翻乱。同一事务里两次读落在同一快照上
 * （MySQL/InnoDB 的 REPEATABLE READ、PostgreSQL 的可重复读快照都提供这件事），总数与条目必然自洽。
 *
 * <p><b>行限制子句不写死</b>：本页最后一段 {@code limit ? offset ?} 还是 {@code offset ? rows fetch next ? rows only}
 * 由 {@link PagingClause} 在拿到连接的那一刻按目标库的产品名决定，两种形态的子句文本与参数绑定顺序同在一处。
 *
 * <p><b>{@code requeue} 不开事务</b>：它是一条带 {@code status = 'DEAD'} 谓词的单语句 {@code update}，
 * 原子性由数据库保证；这里再套一层事务只会让批量重放长时间占着连接。
 *
 * @see JdbcOutboxStore 同一张表的投递（写）侧实现
 * @see PagingClause 分页子句的方言表
 */
public final class JdbcOutboxDeadLetterStore implements OutboxDeadLetterStore {

    private final OutboxSql sql;
    private final TransactionOperations readTransaction;
    private final int queryLimit;

    /**
     * @param dataSource      目标库数据源；路由模式下注入的是带 {@code @Primary} 的路由数据源
     * @param readTransaction count 与 select 的执行事务（装配时是 {@code TransactionTemplate}）
     * @param table           Outbox 表名，形状必须过 {@link OutboxSql} 的守卫
     * @param queryLimit      单页与单批的上限，必须 ≥ 1
     * @throws FrameworkException 表名非法，或 {@code queryLimit < 1}
     */
    public JdbcOutboxDeadLetterStore(DataSource dataSource, TransactionOperations readTransaction, String table,
            int queryLimit) {
        this.sql = new OutboxSql(Objects.requireNonNull(dataSource, "死信读侧的数据源不能为 null"), table);
        this.readTransaction = Objects.requireNonNull(readTransaction,
                "死信查询需要一个事务执行器：装配时由 TransactionTemplate 提供");
        if (queryLimit < 1) {
            throw new FrameworkException("myddd.persistence.outbox.dead-letter.query-limit 必须 ≥ 1，实际 "
                    + queryLimit + "；0 或负数不会报错，只会让死信列表永远是空的——那种静默比启动失败难查得多");
        }
        this.queryLimit = queryLimit;
    }

    @Override
    public PageResult<OutboxRecord> findDead(Instant from, Instant to, PageQuery page) {
        Objects.requireNonNull(page, "死信查询的分页参数不能为 null");
        if (from != null && to != null && !to.isAfter(from)) {
            throw new FrameworkException("死信查询的时间窗要求上界严格晚于下界：from=" + from + "、to=" + to
                    + "；相等或反向都只是一个空窗口，但更常见的情形是运维界面把两个值填反了，"
                    + "所以这里直接失败并把两个取值打出来");
        }
        long pageSize = Math.min(page.getPageSize(), queryLimit);
        long offset = PageQuery.of(page.getPageNum(), pageSize).offset();
        Window window = new Window(from, to);
        return readTransaction.execute(status -> {
            long total = countMatching(window);
            if (total == 0) {
                return PageResult.empty(page.getPageNum(), pageSize);
            }
            String select = "select " + OutboxSql.columnList() + " from " + sql.table() + window.where()
                    + " order by created_at desc, event_id desc";
            List<OutboxRecord> items = sql.execute("findDead", () -> sql.jdbc().query(
                    PagingClause.paged(select, (ps, start) -> bind(ps, window.args(), start), pageSize, offset),
                    (rs, rowNum) -> OutboxSql.toRecord(rs)));
            return PageResult.of(items, total, page.getPageNum(), pageSize);
        });
    }

    private long countMatching(Window window) {
        List<Object> args = window.args();
        // 抽取器恒返回一个 long（拆箱处的 null 分支演不到，留着就是往交付里塞一段假防御）；
        // 剩下唯一不覆盖的一侧是 `rs.next()` 为假——`select count(*)` 在任何受支持库里都恰好回一行。
        return sql.execute("findDead", () -> (long) sql.jdbc().query(
                "select count(*) from " + sql.table() + window.where(),
                (PreparedStatementSetter) ps -> bind(ps, args, 1),
                rs -> rs.next() ? rs.getLong(1) : 0L));
    }

    @Override
    public int requeue(List<String> eventIds, Instant now) {
        Objects.requireNonNull(now, "重放时刻 now 不能为 null：它同时是这些记录新的 next_retry_at");
        if (eventIds == null || eventIds.isEmpty()) {
            return 0;
        }
        if (eventIds.size() > queryLimit) {
            throw new FrameworkException("一次重放最多 " + queryLimit + " 条，实际传入 " + eventIds.size()
                    + " 条：请分批重放。上限来自 myddd.persistence.outbox.dead-letter.query-limit，"
                    + "它存在的意义是让一次误点不至于把整张表的死信同时拽回队列");
        }
        for (String eventId : eventIds) {
            if (eventId == null || eventId.isBlank()) {
                throw new FrameworkException("待重放的 event_id 里有 null 或空白：" + eventIds
                        + "；空白值会被绑进 in (...) 而永远匹配不到行，那种\"调用成功但一行都没重放\"最难排查");
            }
        }
        String update = "update " + sql.table() + " set status = ?, retry_count = ?, next_retry_at = ?, "
                + DdlReference.LEASE_COLUMN + " = null where status = ? and event_id in ("
                + OutboxSql.placeholders(eventIds.size()) + ")";
        return sql.execute("requeue", () -> sql.jdbc().update(OutboxSql.creator(update, ps -> {
            ps.setString(1, OutboxStatus.PENDING.name());
            ps.setInt(2, 0);
            ps.setTimestamp(3, Timestamp.from(now));
            ps.setString(4, OutboxStatus.DEAD.name());
            for (int i = 0; i < eventIds.size(); i++) {
                ps.setString(5 + i, eventIds.get(i));
            }
        })));
    }

    @Override
    public int queryLimit() {
        return queryLimit;
    }

    /** 时间窗 → where 片段与参数序列：两个边界各自可省，下界含、上界不含。 */
    private record Window(Instant from, Instant to) {

        String where() {
            StringBuilder clause = new StringBuilder(" where status = ?");
            if (from != null) {
                clause.append(" and created_at >= ?");
            }
            if (to != null) {
                clause.append(" and created_at < ?");
            }
            return clause.toString();
        }

        List<Object> args() {
            List<Object> args = new ArrayList<>(3);
            args.add(OutboxStatus.DEAD.name());
            if (from != null) {
                args.add(Timestamp.from(from));
            }
            if (to != null) {
                args.add(Timestamp.from(to));
            }
            return args;
        }
    }

    /** 从 1 基下标 {@code start} 起绑一组字面量参数，返回下一个可用下标。 */
    private static int bind(PreparedStatement ps, List<Object> args, int start) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            ps.setObject(start + i, args.get(i));
        }
        return start + args.size();
    }
}
