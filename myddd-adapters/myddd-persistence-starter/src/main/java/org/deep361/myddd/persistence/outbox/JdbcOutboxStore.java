package org.deep361.myddd.persistence.outbox;

import org.deep361.myddd.domain.outbox.OutboxRecord;
import org.deep361.myddd.domain.outbox.OutboxStatus;
import org.deep361.myddd.domain.outbox.OutboxStore;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.persistence.ddl.DdlReference;
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
 * {@link OutboxStore} 的 JDBC 实现：{@code myddd_outbox} 的写入、原子认领与状态覆盖。
 *
 * <p><b>三条契约各自靠什么满足</b>（契约原文见 {@link OutboxStore} 的类 javadoc，每一条都有对应用例在
 * H2 上跑真 SQL 取证，见 {@code JdbcOutboxStoreTest}）：
 * <ol>
 *   <li><b>{@code save} 参与调用方当前事务</b>：全部语句经 {@link JdbcTemplate} 执行，而它取连接走的是
 *       {@code DataSourceUtils.getConnection}——有活动事务时拿到的是那条<b>已绑定</b>的连接，因此记录与业务改动
 *       同提交同回滚。本方法刻意<b>不</b>自己开事务（自己开会把它从业务事务里摘出去，正是要防的那种错）。</li>
 *   <li><b>{@code claimDispatchable} 一次原子操作完成"选取 + 认领"</b>：先读一份不带锁的候选名单
 *       （条数由 JDBC 标准的 {@code setMaxRows} 决定，SQL 文本里没有行限制子句），再对每一条跑一次
 *       <b>带"仍可认领"谓词的 {@code update}</b>——判定与改写发生在同一条语句里，原子性由数据库自己保证，
 *       两个实例盯上同一行时只有一个拿到 1 行。整批跑在同一个事务回调（{@link TransactionOperations}，
 *       装配时是 {@code TransactionTemplate}）里，因而同连接、少一次往返。
 *       <b>刻意不用 {@code for update skip locked}</b>：那是 MySQL 8 / PostgreSQL / Oracle 才有的方言，
 *       本框架的设计目标是同一套代码换库能跑，认领的正确性不能建在某个库的行锁选项上（见
 *       {@link #claimBatch} 的说明）。</li>
 *   <li><b>线程安全</b>：无可变状态（唯一的 {@code statementProven} 只是"是否已经成功执行过一条语句"的提示开关，
 *       写多读少且不参与正确性）。</li>
 * </ol>
 *
 * <p><b>列名与表名都只有真源</b>：读写列表来自 {@link DdlReference#outboxRecordColumns()}（即
 * {@link OutboxRecord} 的 record 组件），表名来自配置键且必须过
 * {@value OutboxSql#TABLE_NAME_RULE} 的形状校验——它会原样拼进 SQL 文本，这是本类唯一的字符串拼接注入面，
 * 所以宁可构造失败也不猜。租约列 {@link DdlReference#LEASE_COLUMN} 只出现在认领的两条语句里，
 * 不进 {@code OutboxRecord} 的读写列表。
 *
 * <p><b>两处刻意的取舍</b>：① {@code last_error} 超长时截断而不是让写入失败——重试与死信的落库路径如果因为一条
 * 异常消息太长而整体失败，坏记录会永远卡在 {@code DISPATCHING} 与 {@code PENDING} 之间循环；② 首次执行就遇到
 * SQL 语法/对象错误时，把驱动原文换成一句指名"参考建表脚本在哪"的
 * {@link FrameworkException}（表没建是这一层最常见的首跑错误），一旦有语句成功过，之后的语法错误原样上抛，
 * 不再冒充建表提示。
 */
public final class JdbcOutboxStore implements OutboxStore {

    /**
     * {@code last_error} 的列宽，与交付 DDL 的 {@code last_error varchar(1000)} 同值。
     * 由 {@code JdbcOutboxStoreTest} 拿 DDL 文本核对，改 DDL 忘了改这里会以测试失败收场。
     */
    static final int LAST_ERROR_MAX_LENGTH = 1000;

    private final OutboxSql sql;
    private final TransactionOperations claimTransaction;
    private final String insertSql;
    /** 候选集查询：只读、不加锁、不带行限制子句，条数由 {@code setMaxRows} 决定。 */
    private final String claimableSql;
    /** 单条比较并交换认领：只有行仍处于候选谓词里才写得上，返回 1 才算认领成功。 */
    private final String claimSql;
    private final String updateSql;

    /**
     * "这条记录现在可被认领"的谓词，候选集查询与认领语句**共用同一份**：两处各写一遍就会出现
     * "选得到却抢不到"或"抢得到却不该选"那种分叉。参数顺序固定为
     * {@code PENDING, now, DISPATCHING, now}（租约为 null 的 {@code DISPATCHING} 行不满足
     * {@code lease < ?}，本来就不该被重新认领）。
     *
     * <p><b>最外层那对括号是承重的</b>：SQL 里 {@code and} 比 {@code or} 绑定更紧，认领那句拼出来是
     * {@code where event_id = ? and (A) or (B)}——没有外层括号时它等于 {@code (event_id = ? and A) or (B)}，
     * {@code (B)} 这一支上没有任何 {@code event_id} 约束，一条本该只锁一行的比较并交换会顺手把<b>别的</b>
     * 过期租约记录一起改成 DISPATCHING，而 {@code update} 的返回值变成 2、这一行反倒算认领失败。
     */
    private static final String DISPATCHABLE = "((status = ? and next_retry_at <= ?) or (status = ? and "
            + DdlReference.LEASE_COLUMN + " < ?))";

    /**
     * @param dataSource       目标库的数据源；路由模式下注入的是带 {@code @Primary} 的路由数据源，
     *                         事务连接由 {@code DataSourceUtils} 解析，本类不自己 {@code getConnection()}
     * @param claimTransaction 认领批次的执行事务：生产装配用 {@code TransactionTemplate}（传播行为 {@code REQUIRED}，
     *                         外层已有事务时加入，没有时自己开一个——认领必须原子，不能指望调用方恰好开了事务）
     * @param table            表名，取值必须匹配 {@value OutboxSql#TABLE_NAME_RULE}；缺省用
     *                         {@link DdlReference#OUTBOX_TABLE}
     * @throws FrameworkException 表名非法（会拼进 SQL，不接受任何引号、空白或分号形态）
     */
    public JdbcOutboxStore(DataSource dataSource, TransactionOperations claimTransaction, String table) {
        this.sql = new OutboxSql(Objects.requireNonNull(dataSource, "Outbox 存储的数据源不能为 null"), table);
        this.claimTransaction = Objects.requireNonNull(claimTransaction,
                "Outbox 认领需要一个事务执行器：装配时由 TransactionTemplate 提供");
        String columnList = OutboxSql.columnList();
        this.insertSql = "insert into " + sql.table() + " (" + columnList + ") values ("
                + OutboxSql.placeholders(DdlReference.outboxRecordColumns().size()) + ")";
        this.claimableSql = "select " + columnList + " from " + sql.table()
                + " where " + DISPATCHABLE + " order by created_at, event_id";
        this.claimSql = "update " + sql.table() + " set status = ?, " + DdlReference.LEASE_COLUMN
                + " = ? where event_id = ? and " + DISPATCHABLE;
        this.updateSql = "update " + sql.table() + " set status = ?, retry_count = ?, next_retry_at = ?, last_error = ?, "
                + DdlReference.LEASE_COLUMN + " = null where event_id = ?";
    }

    @Override
    public void save(OutboxRecord record) {
        Objects.requireNonNull(record, "待保存的 Outbox 记录不能为 null");
        if (record.status() != OutboxStatus.PENDING) {
            throw new FrameworkException("Outbox 存储只接受 PENDING 记录，实际 " + record.status() + "："
                    + record.eventId() + "；状态流转要走 update(...)，认领要走 claimDispatchable(...)");
        }
        sql.execute("save", () -> sql.jdbc().update(OutboxSql.creator(insertSql, ps -> bindRecord(ps, record))));
    }

    @Override
    public List<OutboxRecord> claimDispatchable(Instant now, Instant leaseUntil, int limit) {
        Objects.requireNonNull(now, "认领的当前时间 now 不能为 null");
        Objects.requireNonNull(leaseUntil, "认领的租约到期时间 leaseUntil 不能为 null");
        if (limit < 1) {
            throw new FrameworkException("claimDispatchable 的 limit 必须 ≥ 1，实际 " + limit
                    + "；0 会让每轮投递都白扫一次表，负数则直接是 SQL 错误");
        }
        if (!leaseUntil.isAfter(now)) {
            throw new FrameworkException("claimDispatchable 的租约到期时间必须晚于 now：now=" + now
                    + "、leaseUntil=" + leaseUntil + "；租约一到期记录就会被别的实例重新认领，等于没有租约");
        }
        return claimTransaction.execute(status -> claimBatch(now, leaseUntil, limit));
    }

    @Override
    public void update(OutboxRecord record) {
        Objects.requireNonNull(record, "待更新的 Outbox 记录不能为 null");
        int rows = sql.execute("update", () -> sql.jdbc().update(OutboxSql.creator(updateSql, ps -> {
            ps.setString(1, record.status().name());
            ps.setInt(2, record.retryCount());
            ps.setTimestamp(3, Timestamp.from(record.nextRetryAt()));
            ps.setString(4, trimLastError(record.lastError()));
            ps.setString(5, record.eventId());
        })));
        if (rows == 0) {
            throw new FrameworkException("Outbox 更新影响了 0 行：eventId=" + record.eventId()
                    + " 不在 " + sql.table() + " 表里。update 只覆盖已落库的记录，新记录要走 save(...)");
        }
    }

    /**
     * 候选集读取 + 逐条比较并交换认领：跑在 {@link #claimTransaction} 的同一个回调里，因而同连接、同事务。
     *
     * <p>候选集只是"看起来可认领"的一份名单，不持有任何锁；真正决定归属的是那条带 {@value #DISPATCHABLE}
     * 谓词的单语句 {@code update}——它把"是否还可认领"和"改写状态"压进同一次原子操作里，所以两个实例同时
     * 盯上同一行时，只有一个拿得到 1 行，另一个拿到 0 行。**认领的正确性因此不依赖任何行锁方言**
     * （{@code for update skip locked} 只有 MySQL 8 / PostgreSQL / Oracle 有，MariaDB 10.6 以前、
     * SQL Server 与 H2 的非 MySQL 模式都没有，而本框架的设计目标是同一套代码换库能跑）。
     */
    private List<OutboxRecord> claimBatch(Instant now, Instant leaseUntil, int limit) {
        List<OutboxRecord> candidates = sql.execute("claimDispatchable",
                () -> sql.jdbc().query(OutboxSql.limited(claimableSql, limit, ps -> {
                    ps.setString(1, OutboxStatus.PENDING.name());
                    ps.setTimestamp(2, Timestamp.from(now));
                    ps.setString(3, OutboxStatus.DISPATCHING.name());
                    ps.setTimestamp(4, Timestamp.from(now));
                }), (rs, rowNum) -> OutboxSql.toRecord(rs)));
        List<OutboxRecord> claimed = new ArrayList<>(candidates.size());
        for (OutboxRecord candidate : candidates) {
            if (claimOne(candidate.eventId(), now, leaseUntil) == 1) {
                claimed.add(candidate.dispatching());
            }
        }
        return List.copyOf(claimed);
    }

    /** 单条认领：返回受影响行数（1 = 抢到，0 = 已被别的实例认领或已到期条件不再成立）。 */
    private int claimOne(String eventId, Instant now, Instant leaseUntil) {
        return sql.execute("claimDispatchable", () -> sql.jdbc().update(OutboxSql.creator(claimSql, ps -> {
            ps.setString(1, OutboxStatus.DISPATCHING.name());
            ps.setTimestamp(2, Timestamp.from(leaseUntil));
            ps.setString(3, eventId);
            ps.setString(4, OutboxStatus.PENDING.name());
            ps.setTimestamp(5, Timestamp.from(now));
            ps.setString(6, OutboxStatus.DISPATCHING.name());
            ps.setTimestamp(7, Timestamp.from(now));
        })));
    }

    private static void bindRecord(PreparedStatement ps, OutboxRecord record) throws SQLException {
        int i = 1;
        ps.setString(i++, record.eventId());
        ps.setString(i++, record.eventType());
        ps.setString(i++, record.aggregateType());
        ps.setString(i++, record.aggregateId());
        ps.setString(i++, record.traceId());
        ps.setTimestamp(i++, Timestamp.from(record.occurredAt()));
        ps.setString(i++, record.channel());
        ps.setString(i++, record.payload());
        ps.setString(i++, record.status().name());
        ps.setInt(i++, record.retryCount());
        ps.setTimestamp(i++, Timestamp.from(record.nextRetryAt()));
        ps.setString(i++, trimLastError(record.lastError()));
        ps.setTimestamp(i, Timestamp.from(record.createdAt()));
    }

    private static String trimLastError(String lastError) {
        if (lastError == null || lastError.length() <= LAST_ERROR_MAX_LENGTH) {
            return lastError;
        }
        return lastError.substring(0, LAST_ERROR_MAX_LENGTH);
    }

}
