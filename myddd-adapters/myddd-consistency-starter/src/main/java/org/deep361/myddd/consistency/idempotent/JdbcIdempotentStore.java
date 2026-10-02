package org.deep361.myddd.consistency.idempotent;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.deep361.myddd.consistency.autoconfigure.MydddConsistencyProperties;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.consistency.IdempotentPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link IdempotentPort} 的数据库实现（{@code myddd.consistency.idempotent.store=db}）：
 * {@code myddd_idempotent_record} 表上的<b>唯一键插入</b>占位。
 *
 * <p><b>为什么要有第二种存储</b>：不是"没有 Redis 时的替代品"这么简单。幂等记录常常必须与业务数据同库、
 * 同一个事务边界、同一条 {@code select} 可查——客服问"这单为什么报重复提交"时，DBA 要能直接查出那一行，
 * 而不是先去 Redis 里按模式扫。这一支适用于：没有 Redis 的应用、审计要求占位可查的应用、
 * 以及"占位必须和业务改动一起提交/一起回滚"的应用（这一支与业务事务的关系见下面最后一段）。
 *
 * <p><b>三条语义各自靠哪一条语句</b>（端口契约原文见 {@link IdempotentPort}）：
 * <ol>
 *   <li><b>占位 = 一条 {@code insert}</b>。主键冲突（{@link DuplicateKeyException}）就是"已经有人占着"，
 *       所以刻意<b>不</b>做"先 select 看一眼再 insert"——那两步之间的窗口正是幂等要防的那个窗口。
 *       "占位 + 绑定 token + 设定过期"必须落在同一行写入里，等价于 Redis 侧的 {@code SET key token NX PX}。</li>
 *   <li><b>过期 = {@code expires_at <= now}，由读侧算出来</b>。Redis 到点自己删，DB 没人删，所以
 *       {@code IdempotentPort} 的类注释要求"DB 实现须把过期行视为占位不存在"。落点是冲突以后紧跟的
 *       那条带 {@code and expires_at <= ?} 的 {@code update}：行还新鲜 → 0 行 → {@code false}；
 *       行已过期 → 1 行且 token 换成我的 → {@code true}。两个进程同时抢同一条过期行时，InnoDB 的行锁把
 *       第二条 {@code update} 挡在第一条提交之后，而 {@code UPDATE} 走的是当前读（不受快照隔离级别影响），
 *       于是它会用<b>新的</b> {@code expires_at} 重新求值谓词并拿到 0 行——这与 {@code SET NX} 等价，
 *       不需要额外一把锁，也不需要 {@code for update}。</li>
 *   <li><b>释放 = 一条 {@code delete ... where idem_key = ? and token = ?}</b>。比对与删除在同一条语句里
 *       完成，等价于 Redis 侧那段"GET 比对了才 DEL"的 Lua。少掉 {@code token} 谓词就是"慢请求把别人的
 *       占位删了"：动作跑得太慢、占位已过期、别的请求重新占上，此时无条件按主键删会让第三个请求畅通无阻，
 *       幂等被静默击穿，而且只在慢请求上出现。0 行是正确结果（端口契约：token 不一致或占位不存在时空操作），
 *       不是错误。</li>
 * </ol>
 *
 * <p><b>过期行的清理</b>：{@link #purgeExpired(Instant, int)}。读侧的"过期即不存在"已经保证了<b>正确性</b>，
 * 清理管的是<b>存储不会只涨不落</b>——Redis 的 TTL 顺手做掉了这件事，DB 必须自己做。交付的参考 DDL 里那条
 * {@code idx_myddd_idempotent_expires_at} 就是为这个扫描建的。清理与占位可以并发，所以删除语句必须把
 * {@code expires_at <= ?} 谓词<b>再判一次</b>：只按上一步查出来的主键删，会删掉"被查出来以后刚刚被别人
 * 续期认领"的那一行，那等于把别人的占位删了。加了谓词就不需要事务，也不需要 {@code for update}。
 *
 * <p><b>两处刻意的取舍</b>：① 时间戳一律截断到<b>毫秒</b>——列是 {@code datetime(3)}，纳秒会被驱动/服务器
 * <b>四舍五入</b>，于是一条占位的实际存活时间可能比要求的长 0.5 毫秒，而用例要判"过期"就必须知道
 * 存储侧真正的到期时刻；截断以后写进去的就是读出来的那个值。② {@code idem_key} 超长（列宽
 * {@value #MAX_KEY_LENGTH}）在写入前先拒——让它到 MySQL 里报"data too long"，用户看到的是一句和
 * 字段长度有关、却指名不到框架的驱动错误。
 *
 * <p><b>没有本地缓存、没有 {@code exists} 预判、没有 {@code select} 预读</b>：与 Redis 侧同一条纪律。
 *
 * <p><b>表名与列名</b>：表名来自 {@code myddd.consistency.idempotent.db.table}，构造期过
 * {@link IdempotentStoreGuards#TABLE_NAME_RULE} 形状校验（它会原样拼进 SQL 文本，这是本类唯一的
 * 字符串拼接注入面）；列名只有 {@link #COLUMNS} 一处，四条语句全部由它拼出。表结构本身与
 * {@code myddd-persistence-starter} 交付的 {@code db/ddl-reference/V2__myddd_idempotent_record.sql}
 * 逐列对应，两侧各有一份核对用例（本模块 {@code JdbcIdempotentStoreTest} 与那边的
 * {@code DdlReferenceTest}），改列名要同时改两处才会绿。
 *
 * <p><b>与业务事务的关系</b>：语句经 {@link JdbcTemplate} 执行，取连接走 {@code DataSourceUtils}，
 * 因此容器里有活动事务时占位<b>参与</b>那个事务。这不是"支持"而是一种必须警惕的耦合：占位参与业务事务意味着
 * 业务回滚会把占位一起回滚掉（重复提交于是又成首次），而占位想在业务提交前先写进去也必须等这个事务。
 * 所以缺省实现照旧是 Redis；真要用 DB 支路做"防重复提交"，请把带 {@code @Idempotent} 的方法放在
 * 业务事务<b>之外</b>（幂等切面是 {@code @Order(-1)}，本来就排在事务 advisor 前面，外层不要再套一层
 * 事务把它一起包住）。
 */
public class JdbcIdempotentStore implements IdempotentPort {

    /** 缺省表名，同时是 {@code myddd.consistency.idempotent.db.table} 的默认值。 */
    public static final String DEFAULT_TABLE = "myddd_idempotent_record";

    /** 配置键名，只服务于失败话术（让用户知道该改哪一行）。 */
    static final String TABLE_CONFIG_KEY = "myddd.consistency.idempotent.db.table";

    static final String COLUMN_KEY = "idem_key";
    static final String COLUMN_TOKEN = "token";
    static final String COLUMN_CREATED_AT = "created_at";
    static final String COLUMN_EXPIRES_AT = "expires_at";

    /**
     * 全部物理列，顺序即 {@code insert} 的列序。与交付 DDL 的对应关系由本模块与持久化模块各一份用例钉住
     * （见类注释）；列宽 {@value #MAX_KEY_LENGTH} 同样来自那份 DDL。
     */
    static final List<String> COLUMNS = List.of(COLUMN_KEY, COLUMN_TOKEN, COLUMN_CREATED_AT, COLUMN_EXPIRES_AT);

    /** {@code idem_key} 的列宽，与交付 DDL 的 {@code idem_key varchar(255)} 同值。 */
    static final int MAX_KEY_LENGTH = 255;

    private final JdbcTemplate jdbc;
    private final String table;
    private final String keyPrefix;
    private final Clock clock;

    private final String insertSql;
    private final String stealSql;
    private final String deleteSql;
    private final String selectExpiredSql;

    /** 是否已经成功执行过至少一条语句——只决定语法错误要不要配建表提示，不参与任何业务判定。 */
    private volatile boolean statementProven;

    /**
     * @param dataSource 目标库的数据源；路由模式下注入的是带 {@code @Primary} 的路由数据源
     * @param properties 幂等键前缀段（{@code myddd.consistency.idempotent.key-prefix}）的来源
     * @param table      表名，缺省用 {@link #DEFAULT_TABLE}
     * @throws FrameworkException 表名空白或形状非法（会拼进 SQL，不接受任何引号、空白或分号形态）
     */
    public JdbcIdempotentStore(DataSource dataSource, MydddConsistencyProperties properties, String table) {
        this(dataSource, properties, table, Clock.systemUTC());
    }

    JdbcIdempotentStore(DataSource dataSource, MydddConsistencyProperties properties, String table, Clock clock) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "幂等占位表的数据源不能为 null"));
        this.table = IdempotentStoreGuards.requireTableName(table, TABLE_CONFIG_KEY);
        this.keyPrefix = properties.getIdempotent().getKeyPrefix();
        this.clock = clock;
        String columns = String.join(", ", COLUMNS);
        this.insertSql = "insert into " + this.table + " (" + columns + ") values (?, ?, ?, ?)";
        this.stealSql = "update " + this.table + " set " + COLUMN_TOKEN + " = ?, " + COLUMN_CREATED_AT + " = ?, "
                + COLUMN_EXPIRES_AT + " = ? where " + COLUMN_KEY + " = ? and " + COLUMN_EXPIRES_AT + " <= ?";
        this.deleteSql = "delete from " + this.table + " where " + COLUMN_KEY + " = ? and " + COLUMN_TOKEN + " = ?";
        this.selectExpiredSql = "select " + COLUMN_KEY + " from " + this.table + " where " + COLUMN_EXPIRES_AT
                + " <= ? order by " + COLUMN_EXPIRES_AT + ", " + COLUMN_KEY;
    }

    @Override
    public boolean tryAcquire(String key, String token, Duration ttl) {
        String placeholder = physicalKey(key, token, ttl);
        Instant now = this.clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Instant expiresAt = now.plus(ttl);
        return execute("tryAcquire", () -> {
            try {
                return this.jdbc.update(this.insertSql, ps -> {
                    ps.setString(1, placeholder);
                    ps.setString(2, token);
                    ps.setTimestamp(3, Timestamp.from(now));
                    ps.setTimestamp(4, Timestamp.from(expiresAt));
                }) == 1;
            } catch (DuplicateKeyException taken) {
                return steal(placeholder, token, now, expiresAt) == 1;
            }
        });
    }

    @Override
    public void release(String key, String token) {
        IdempotentStoreGuards.requireKey(key);
        IdempotentStoreGuards.requireToken(token);
        String placeholder = idempotentKey(key);
        execute("release", () -> this.jdbc.update(this.deleteSql, ps -> {
            ps.setString(1, placeholder);
            ps.setString(2, token);
        }));
    }

    /**
     * 清掉到期行，返回<b>实际删除</b>的行数（可能少于查出来的条数：被别人续期认领的那些不能删）。
     *
     * <p>本方法刻意不在 {@link IdempotentPort} 上，也不由框架自己定时调用：占位表归业务库所有，
     * 清理节拍（多久一次、一次多少条、要不要留审计）是运维决定。框架交付的是那个"择机清理过期行"的能力，
     * 接线（比如 {@code @MydddJob}）在业务侧。
     *
     * @param now   判定到期的时刻，通常传 {@code Instant.now()}
     * @param limit 单批条数上限，必须 ≥ 1；一次删太多会长时间持有行锁
     */
    public int purgeExpired(Instant now, int limit) {
        Objects.requireNonNull(now, "清理的判定时刻 now 不能为 null");
        if (limit < 1) {
            throw new FrameworkException("purgeExpired 的 limit 必须 ≥ 1，实际 " + limit
                    + "；0 会让每轮清理白扫一次表，负数则直接是 SQL 错误");
        }
        return execute("purgeExpired", () -> {
            Timestamp bound = Timestamp.from(now);
            List<String> expired = this.jdbc.query(connection -> {
                PreparedStatement ps = connection.prepareStatement(this.selectExpiredSql);
                // 单批条数走 JDBC 标准的 setMaxRows：limit / fetch first / rownum 三种写法互不通用，
                // 写进 SQL 文本就等于把这个存储绑死在一个数据库上。
                ps.setMaxRows(limit);
                ps.setTimestamp(1, bound);
                return ps;
            }, (rs, rowNum) -> rs.getString(1));
            if (expired.isEmpty()) {
                return 0;
            }
            Object[] args = new Object[expired.size() + 1];
            args[0] = bound;
            for (int i = 0; i < expired.size(); i++) {
                args[i + 1] = expired.get(i);
            }
            return this.jdbc.update(deleteExpiredSql(expired.size()), args);
        });
    }

    /**
     * 清理的第二步：按主键删，但<b>再判一次</b>到期谓词。
     *
     * <p>单独一个方法是为了让那条谓词成为可断言的对象——"查出来以后被别人续期认领的行不能删"这个要求
     * 在单线程用例里演不出来（那两步之间没有别人），只能钉形状。
     */
    String deleteExpiredSql(int count) {
        return "delete from " + this.table + " where " + COLUMN_EXPIRES_AT + " <= ? and " + COLUMN_KEY
                + " in (" + String.join(", ", java.util.Collections.nCopies(count, "?")) + ")";
    }

    /**
     * 占位已存在时的一次"到期认领"：只在那一行确实过期时才换主人。
     *
     * <p>返回 1 表示抢到，0 表示别人还持有有效占位。0 行也可能是"这一行刚被清理线程删掉"：那时本次按重复
     * 请求处理（{@code false}），方向是<b>宁可多拒一次也不多发一次</b>——幂等端口拒绝一个请求只是让用户
     * 稍后再点一次，放行两个请求则是重复下单。
     */
    private int steal(String placeholder, String token, Instant now, Instant expiresAt) {
        return this.jdbc.update(this.stealSql, ps -> {
            ps.setString(1, token);
            ps.setTimestamp(2, Timestamp.from(now));
            ps.setTimestamp(3, Timestamp.from(expiresAt));
            ps.setString(4, placeholder);
            ps.setTimestamp(5, Timestamp.from(now));
        });
    }

    /** 占位的物理键名：{@code keyPrefix + ":" + key}，与 Redis 侧同一个串（等价用例 EQ 组钉住）。 */
    public String idempotentKey(String key) {
        return IdempotentStoreGuards.prefixedKey(this.keyPrefix, key);
    }

    /**
     * 表名（已过形状校验）。与 {@link #idempotentKey(String)} 同样公开：装配面的用例要能断言
     * "配置里那一个字真的走到了 SQL 里"，而不是只断言 bean 建出来了。
     */
    public String table() {
        return this.table;
    }

    /** 四条语句的原文，只给用例看（钉列序与谓词形状，不参与任何运行期判定）。 */
    String insertSql() {
        return this.insertSql;
    }

    String stealSql() {
        return this.stealSql;
    }

    String deleteSql() {
        return this.deleteSql;
    }

    String selectExpiredSql() {
        return this.selectExpiredSql;
    }

    private String physicalKey(String key, String token, Duration ttl) {
        IdempotentStoreGuards.requireKey(key);
        IdempotentStoreGuards.requireToken(token);
        IdempotentStoreGuards.requireTtl(ttl);
        String placeholder = idempotentKey(key);
        if (placeholder.length() > MAX_KEY_LENGTH) {
            throw new FrameworkException("幂等键 \"" + placeholder + "\" 长 " + placeholder.length() + " 个字符，超过 "
                    + TABLE_CONFIG_KEY + " 指向的表里 " + COLUMN_KEY + " 的列宽 " + MAX_KEY_LENGTH
                    + "：前缀段（myddd.consistency.idempotent.key-prefix）与 @Idempotent(key=...) 拼出来的串"
                    + "必须放得进这一列。请把键收敛成业务坐标（订单号、请求号），别把整个 payload 拼进 SpEL；"
                    + "确实需要长键请把列改成 varchar(512) 或 varbinary 摘要");
        }
        return placeholder;
    }

    /**
     * 包一层语法错误的处理：首次执行就报语法/对象错误时给出建表提示，之后原样上抛。
     *
     * <p>与持久化侧 {@code OutboxSql} 里同名的逻辑是<b>两份</b>，因为那一个是包私有的、跨不到本模块；
     * 两侧的行为要求完全一样（"表还没建"是这一层最常见的首跑错误，必须指名参考脚本在哪，而一旦有语句
     * 成功过，之后的语法错就是我们自己的 SQL 错了，原样上抛不冒充建表提示）。
     */
    private <T> T execute(String task, Supplier<T> action) {
        try {
            T result = action.get();
            this.statementProven = true;
            return result;
        } catch (BadSqlGrammarException failure) {
            if (this.statementProven) {
                throw failure;
            }
            throw new FrameworkException(task + " 失败：SQL 语法或对象不存在（表 " + this.table + "）。首次执行就报这个错，"
                    + "最常见的原因是表还没建或 " + TABLE_CONFIG_KEY + " 指错了表名——参考建表脚本在 classpath:"
                    + "db/ddl-reference/V2__myddd_idempotent_record.sql（由 myddd-persistence-starter 交付，"
                    + "按同目录 README.md 复制进你自己的迁移序列，框架不会自动注入它）。原始错误："
                    + failure.getMessage(), failure);
        }
    }
}
