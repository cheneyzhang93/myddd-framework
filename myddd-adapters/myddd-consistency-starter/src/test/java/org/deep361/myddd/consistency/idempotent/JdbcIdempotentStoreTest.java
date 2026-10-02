package org.deep361.myddd.consistency.idempotent;

import java.time.Duration;
import java.time.Instant;

import org.deep361.myddd.consistency.autoconfigure.MydddConsistencyProperties;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JdbcIdempotentStore} 的<b>本支路专属</b>验收：跑真 SQL（H2 的 {@code MODE=MySQL} +
 * 交付 DDL 的原文副本），但只钉那些进不了等价性口径的东西——表名守卫、列宽、到期扫描与清理、
 * 首跑建表提示、以及"占位参与调用方事务"这条与 Redis 侧根本不同的性质。
 *
 * <p>与 {@link JdbcIdempotentStoreContractTest}（EQ 组，两种存储共用一份判定）的分工：那边管
 * "换存储答案不许变"，这边管"DB 这一支自己的形状"。Redis 命令形状在 {@code RedisIdempotentStoreTest}（RS 组）。
 */
class JdbcIdempotentStoreTest {

    /** 整毫秒起点：{@code datetime(3)} 是毫秒精度，用例不靠四舍五入活着。 */
    private static final Instant T0 = Instant.ofEpochMilli(1_700_000_000_000L);
    private static final Duration TTL = Duration.ofSeconds(60);

    private IdempotentH2 db;
    private MutableClock clock;
    private JdbcIdempotentStore store;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        this.db = IdempotentH2.open();
        this.clock = new MutableClock(T0);
        this.store = storeOn(this.db, IdempotentH2.REFERENCE_TABLE);
        this.jdbc = this.db.jdbc();
    }

    // ---------------------------------------------------------------- 形状与守卫

    /**
     * DQ1 测试夹具那份副本必须与 {@link JdbcIdempotentStore} 用到的列一一对上。
     *
     * <p>这条是"副本代替依赖"（见 {@link IdempotentH2} 的类注释）的代价补偿：副本一旦与代码用到的列名、
     * 列宽脱钩，本支路的其它用例会全部继续绿而生产的 {@code insert} 当场失败。另一端的同名清单在
     * {@code myddd-persistence-starter} 的 {@code DdlReferenceTest}（那边把交付物与同一组四列对上）。
     */
    @Test
    void should_have_been_copied_from_the_delivered_reference_ddl() {
        String ddl = IdempotentH2.REFERENCE_DDL;

        assertThat(JdbcIdempotentStore.COLUMNS).containsExactly("idem_key", "token", "created_at", "expires_at");
        assertThat(ddl).contains("create table " + JdbcIdempotentStore.DEFAULT_TABLE + " (")
                .contains("idem_key varchar(255) not null")
                .contains("token varchar(64) not null")
                .contains("created_at datetime(3) not null")
                .contains("expires_at datetime(3) not null")
                .contains("primary key (idem_key)")
                .contains("key idx_myddd_idempotent_expires_at (expires_at)")
                .contains("constraint ck_myddd_idempotent_ttl check (expires_at > created_at)");
        // 代码里那两个"从 DDL 抄来的数字"也必须各自与文本对上：改列宽忘改守卫会以这条红收场。
        assertThat(JdbcIdempotentStore.MAX_KEY_LENGTH).isEqualTo(255);
    }

    /** DQ2 四条语句全部由同一份表名与同一份列清单拼出，不给"某一处用了别的列序"留位置。 */
    @Test
    void should_build_every_statement_from_the_same_table_and_column_list() {
        assertThat(this.store.insertSql())
                .isEqualTo("insert into myddd_idempotent_record (idem_key, token, created_at, expires_at)"
                        + " values (?, ?, ?, ?)");
        assertThat(this.store.stealSql())
                .isEqualTo("update myddd_idempotent_record set token = ?, created_at = ?, expires_at = ?"
                        + " where idem_key = ? and expires_at <= ?");
        assertThat(this.store.deleteSql())
                .isEqualTo("delete from myddd_idempotent_record where idem_key = ? and token = ?");
        assertThat(this.store.selectExpiredSql())
                .as("批量上限不在 SQL 里：它走 JDBC 标准的 setMaxRows，见 DQ13 那两条行为用例")
                .isEqualTo("select idem_key from myddd_idempotent_record where expires_at <= ?"
                        + " order by expires_at, idem_key");
    }

    /**
     * DQ3 表名守卫：表名是唯一原样拼进 SQL 文本的用户输入，非法值一律构造期失败。
     *
     * <p>覆盖"分号形态"这一类注入尝试与"大写/引号/空白/超长"这些会破坏形状或比不中的写法。
     *
     * <p>空白与非空白分两条话术钉：空白值的出路是关掉开关（框架的缺省表名总是可用的），
     * 而非空白的非法值必须把允许的形状念出来——把两句话混成一句，会让"写了个 MyDdd 想改回来"的用户
     * 以为只能整个关掉。
     */
    @Test
    void should_refuse_a_table_name_that_is_not_a_bare_lower_case_identifier() {
        for (String blank : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> storeOn(this.db, blank))
                    .as("表名 %s", blank)
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("myddd.consistency.idempotent.db.table")
                    .hasMessageContaining("不能是空白")
                    .hasMessageContaining("myddd.consistency.idempotent.enabled=false");
        }
        for (String illegal : new String[] {"myddd_idempotent_record; drop table t",
                "\"myddd_idempotent_record\"", "MyDdd_Idempotent_Record", "a".repeat(65), "idem-key"}) {
            assertThatThrownBy(() -> storeOn(this.db, illegal))
                    .as("表名 %s", illegal)
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("myddd.consistency.idempotent.db.table")
                    .hasMessageContaining("只允许 [a-z0-9_]{1,64}");
        }
    }

    /** DQ4 缺省表名就是 {@link JdbcIdempotentStore#DEFAULT_TABLE}，与配置类的默认值同一处真源。 */
    @Test
    void should_default_to_the_delivered_table_name() {
        assertThat(new MydddConsistencyProperties().getIdempotent().getDb().getTable())
                .isEqualTo(JdbcIdempotentStore.DEFAULT_TABLE)
                .isEqualTo(IdempotentH2.REFERENCE_TABLE);

        MydddConsistencyProperties properties = properties("myddd:contract");
        JdbcIdempotentStore defaulted = new JdbcIdempotentStore(this.db.dataSource(), properties,
                properties.getIdempotent().getDb().getTable(), this.clock);

        assertThat(defaulted.tryAcquire("default:1", "token-a", TTL)).isTrue();
        assertThat(this.db.hasRow("myddd:contract:default:1")).isTrue();
    }

    /** DQ5 换表名要一路走到 SQL 里：占位落在业务自己那张表上，框架表一动不动。 */
    @Test
    void should_honour_a_custom_table_name_all_the_way_into_the_sql() {
        IdempotentH2 custom = IdempotentH2.open("acme_idempotent");

        JdbcIdempotentStore moved = storeOn(custom, "acme_idempotent");
        assertThat(moved.insertSql()).startsWith("insert into acme_idempotent (");
        assertThat(moved.tryAcquire("moved:1", "token-a", TTL)).isTrue();
        assertThat(custom.jdbc().queryForObject("select count(*) from acme_idempotent", Integer.class)).isEqualTo(1);
        assertThat(this.db.rowCount()).isZero();
    }

    // ---------------------------------------------------------------- 首跑与守卫话术

    /** DQ6 表还没建：首次语法/对象错误换成"建表脚本在哪"，成功过一次以后原样上抛不冒充提示。 */
    @Test
    void should_point_at_the_reference_script_on_the_first_grammar_error_and_stop_doing_so_afterwards() {
        JdbcTemplate plain = this.db.jdbc();
        plain.execute("drop table myddd_idempotent_record");

        assertThatThrownBy(() -> this.store.tryAcquire("missing:1", "token-a", TTL))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("表还没建")
                .hasMessageContaining("db/ddl-reference/V2__myddd_idempotent_record.sql")
                .hasMessageContaining("myddd.consistency.idempotent.db.table");

        // 重新建表、跑通一条语句以后，同样的错就该以它本来的样子出现：这时候表在，是我们自己的 SQL 错了。
        IdempotentH2 rebuilt = IdempotentH2.open();
        JdbcIdempotentStore proven = storeOn(rebuilt, IdempotentH2.REFERENCE_TABLE);
        assertThat(proven.tryAcquire("ok:1", "token-a", TTL)).isTrue();
        rebuilt.jdbc().execute("drop table myddd_idempotent_record");
        assertThatThrownBy(() -> proven.tryAcquire("ok:2", "token-b", TTL))
                .isInstanceOf(BadSqlGrammarException.class);
    }

    /** DQ7 键超出列宽就在写入前说清楚，而不是让驱动报一句指不到框架的"data too long"。 */
    @Test
    void should_refuse_a_key_longer_than_the_column_before_touching_the_database() {
        String longKey = "k".repeat(JdbcIdempotentStore.MAX_KEY_LENGTH);

        assertThatThrownBy(() -> this.store.tryAcquire(longKey, "token-a", TTL))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("idem_key")
                .hasMessageContaining("255")
                .hasMessageContaining("key-prefix");
        assertThat(this.db.rowCount()).isZero();

        // 刚好放得下的那条必须收：守卫管的是"放不下"，不是"别用长键"。
        assertThat(this.store.tryAcquire(longKey.substring(0, JdbcIdempotentStore.MAX_KEY_LENGTH
                - "myddd:contract:".length()), "token-a", TTL)).isTrue();
    }

    // ---------------------------------------------------------------- 到期与认领

    /** DQ8 已存在的<b>过期</b>行不挡路：认领成功，且三个字段一起换成新占位。 */
    @Test
    void should_treat_an_expired_row_as_no_placeholder_at_all() {
        String physical = this.store.idempotentKey("expired:1");
        this.db.insertRow(physical, "stale-token", T0.minusSeconds(120), T0.minusSeconds(60));
        this.clock.set(T0);

        assertThat(this.store.tryAcquire("expired:1", "fresh-token", TTL)).isTrue();
        assertThat(this.db.tokenOf(physical)).isEqualTo("fresh-token");
        assertThat(this.db.createdAtOf(physical)).isEqualTo(T0);
        assertThat(this.db.expiresAtOf(physical)).isEqualTo(T0.plusSeconds(60));
        assertThat(this.db.rowCount()).isEqualTo(1);
    }

    /** DQ9 还<b>有效</b>的行不许被顶掉：第二次占位返回 false，原占位三个字段一个都不动。 */
    @Test
    void should_not_touch_a_placeholder_that_is_still_alive() {
        String physical = this.store.idempotentKey("alive:1");
        this.db.insertRow(physical, "owner-token", T0, T0.plusSeconds(60));

        assertThat(this.store.tryAcquire("alive:1", "intruder", TTL)).isFalse();
        assertThat(this.db.tokenOf(physical)).isEqualTo("owner-token");
        assertThat(this.db.createdAtOf(physical)).isEqualTo(T0);
        assertThat(this.db.expiresAtOf(physical)).isEqualTo(T0.plusSeconds(60));
    }

    /**
     * DQ10 到期判定的边界是 {@code expires_at <= now}：正好到点那一刻就算过期。
     *
     * <p>这一条钉住"到点即失效"与"到点后还得再等一等"的差别。选 {@code <=} 的理由与 Redis 的 TTL 一致：
     * 存活时长是"到 {@code expires_at} 为止"，那一毫秒本身已经不在窗口里。
     */
    @Test
    void should_consider_the_placeholder_expired_at_the_very_instant_it_dies() {
        String physical = this.store.idempotentKey("boundary:1");
        this.db.insertRow(physical, "owner-token", T0, T0.plusSeconds(60));
        this.clock.set(T0.plusSeconds(60));

        assertThat(this.store.tryAcquire("boundary:1", "next-one", TTL)).isTrue();
        assertThat(this.db.tokenOf(physical)).isEqualTo("next-one");
    }

    /** DQ11 释放只认"这一条键 + 这个 token"：同 token 的别的键、同键的别的 token 都不许被顺手删掉。 */
    @Test
    void should_delete_only_the_row_that_matches_both_key_and_token() {
        this.db.insertRow("myddd:contract:pair-1", "shared-token", T0, T0.plusSeconds(60));
        this.db.insertRow("myddd:contract:pair-2", "shared-token", T0, T0.plusSeconds(60));

        this.store.release("pair-1", "shared-token");
        assertThat(this.db.hasRow("myddd:contract:pair-1")).isFalse();
        assertThat(this.db.hasRow("myddd:contract:pair-2")).isTrue();

        this.store.release("pair-2", "wrong-token");
        assertThat(this.db.hasRow("myddd:contract:pair-2")).isTrue();
    }

    /**
     * DQ12 时间戳一律截断到<b>毫秒</b>：列是 {@code datetime(3)}，纳秒会被四舍五入，
     * 于是"要求活 60 秒"和"实际到期时刻"之间会多出半个毫秒的不确定，边界用例就没法写了。
     */
    @Test
    void should_store_timestamps_truncated_to_the_column_precision() {
        this.clock.set(T0.plusMillis(500).plusNanos(999_000));
        String physical = this.store.idempotentKey("millis:1");

        assertThat(this.store.tryAcquire("millis:1", "token-a", TTL)).isTrue();
        assertThat(this.db.createdAtOf(physical)).isEqualTo(T0.plusMillis(500));
        assertThat(this.db.expiresAtOf(physical)).isEqualTo(T0.plusMillis(500).plusSeconds(60));
    }

    // ---------------------------------------------------------------- 清理

    /** DQ13 清理按到期时间升序、单批限量，而且只删到期的那些。 */
    @Test
    void should_purge_expired_rows_oldest_first_up_to_the_limit() {
        this.db.insertRow("myddd:contract:p3", "t3", T0.plusSeconds(30), T0.plusSeconds(300));
        this.db.insertRow("myddd:contract:p1", "t1", T0, T0.plusSeconds(100));
        this.db.insertRow("myddd:contract:p2", "t2", T0.plusSeconds(10), T0.plusSeconds(200));
        this.db.insertRow("myddd:contract:alive", "ta", T0, T0.plusSeconds(9_000));

        assertThat(this.store.purgeExpired(T0.plusSeconds(500), 2)).isEqualTo(2);
        assertThat(this.db.hasRow("myddd:contract:p1")).isFalse();
        assertThat(this.db.hasRow("myddd:contract:p2")).isFalse();
        assertThat(this.db.hasRow("myddd:contract:p3")).isTrue();
        assertThat(this.db.hasRow("myddd:contract:alive")).isTrue();

        assertThat(this.store.purgeExpired(T0.plusSeconds(500), 10)).isEqualTo(1);
        assertThat(this.store.purgeExpired(T0.plusSeconds(500), 10)).isZero();
        // 没到期的一律不动：now 取早于所有 expires_at 的时刻，一批都删不掉。
        assertThat(this.store.purgeExpired(T0, 10)).isZero();
    }

    /**
     * DQ14 到期时刻相同的那一批按<b>键名本身</b>定序：清理的 {@code limit} 是从"待删集合"里截一段，
     * 排序不完整时同一条键在这一批与下一批之间会来回换位置。
     *
     * <p>这条钉的不是正确性而是可重现性：两个清理线程（或同一批跑两次）必须看到同一份顺序，
     * 否则"这一批删了哪几条"无法从日志里复算，而运维排障时要复算的正是它。
     */
    @Test
    void should_break_a_purge_order_tie_by_the_key_itself() {
        this.db.insertRow("myddd:contract:tie-z", "t", T0, T0.plusSeconds(100));
        this.db.insertRow("myddd:contract:tie-a", "t", T0.plusSeconds(9), T0.plusSeconds(100));

        assertThat(this.store.purgeExpired(T0.plusSeconds(500), 1)).isEqualTo(1);
        assertThat(this.db.hasRow("myddd:contract:tie-a")).isFalse();
        assertThat(this.db.hasRow("myddd:contract:tie-z")).isTrue();
    }

    /**
     * DQ15 删除语句必须把到期谓词<b>再判一次</b>。
     *
     * <p>只按上一步查出来的主键删，就会删掉"被查出来以后刚刚被别人续期认领"的那一行——那等于把别人的
     * 占位删了，而窗口只在清理与请求并发时出现，行为用例演不出来（单线程里那两步之间没有别人），
     * 所以这一条钉的是 SQL 形状。
     */
    @Test
    void should_re_check_the_expiry_predicate_in_the_delete() {
        String sql = this.store.deleteExpiredSql(3);

        assertThat(sql).isEqualTo("delete from myddd_idempotent_record where expires_at <= ?"
                + " and idem_key in (?, ?, ?)");
    }

    /** DQ16 清理的两个入参各自有守卫：limit 非正是白扫，now 为 null 会拼出一个不可比的谓词。 */
    @Test
    void should_refuse_a_purge_batch_without_a_bound_or_a_positive_limit() {
        assertThatThrownBy(() -> this.store.purgeExpired(T0, 0))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("limit 必须 ≥ 1");
        assertThatThrownBy(() -> this.store.purgeExpired(T0, -1))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("limit 必须 ≥ 1");
        assertThatThrownBy(() -> this.store.purgeExpired(null, 10))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("now 不能为 null");
    }

    // ---------------------------------------------------------------- 事务参与

    /**
     * DQ17 占位<b>参与</b>调用方当前事务：外层回滚，那一行跟着消失。
     *
     * <p>这是 DB 支路与 Redis 支路最实质的差别（Redis 没有"跟业务一起回滚"这回事），也是类注释里
     * "请把带 {@code @Idempotent} 的方法放在业务事务之外"那句告诫的全部理由：回滚把占位一起撤掉，
     * 意味着"重复提交"在下一次点击时又成了首次。
     */
    @Test
    void should_join_the_callers_transaction_so_a_rollback_erases_the_placeholder() {
        TransactionTemplate outer = new TransactionTemplate(new DataSourceTransactionManager(this.db.dataSource()));

        assertThatThrownBy(() -> outer.execute(status -> {
            assertThat(this.store.tryAcquire("tx:1", "token-a", TTL)).isTrue();
            throw new IllegalStateException("业务失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(this.db.rowCount()).isZero();

        // 事务之外正常提交：占位留下，第二次请求被判重复。
        assertThat(this.store.tryAcquire("tx:2", "token-a", TTL)).isTrue();
        assertThat(this.store.tryAcquire("tx:2", "token-b", TTL)).isFalse();
    }

    private JdbcIdempotentStore storeOn(IdempotentH2 database, String table) {
        return new JdbcIdempotentStore(database.dataSource(), properties("myddd:contract"), table, this.clock);
    }

    private static MydddConsistencyProperties properties(String keyPrefix) {
        MydddConsistencyProperties properties = new MydddConsistencyProperties();
        properties.getIdempotent().setKeyPrefix(keyPrefix);
        return properties;
    }
}
