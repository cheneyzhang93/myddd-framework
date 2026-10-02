package org.deep361.myddd.persistence.outbox;

import org.deep361.myddd.domain.outbox.OutboxRecord;
import org.deep361.myddd.domain.outbox.OutboxStatus;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.persistence.ddl.DdlReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JdbcOutboxStore} 的行为验收：跑真 SQL，不跑口头断言。
 *
 * <p><b>为什么跑的是真 SQL</b>：库用 H2 的 {@code MODE=MySQL}，而表结构<b>就是交付的那份参考 DDL 原文</b>
 * （{@link DdlReference#OUTBOX_DDL}）——本机实测过这份 DDL 在 H2(MySQL 模式) 上原样建得起来。所以这里没有
 * 第二份 schema，也没有"测试里自己建一张差不多形状"的分叉：列名、列宽、主键、可空性都来自同一段文本。
 *
 * <p><b>认领的原子性在本类里证得了</b>：认领走的是带"仍可认领"谓词的 {@code update}（比较并交换），
 * 它不依赖任何行锁方言，所以"同一行只会被一个持有者拿到""点名一行不许顺手带走邻居"这两条都有真用例
 * （{@code should_cas_claim_only_the_row_it_named_and_not_a_neighbour_with_an_expired_lease} 等）。
 * 早前那版实现用的是 {@code for update skip locked}——正确性躺在某个库的行锁选项上，本类就演不出来，
 * 只能推到真容器上去看；换成比较并交换以后那道推论没有了。
 *
 * <p><b>这里不证明的</b>：驱动 1062 → {@code DuplicateKeyException} 的具体异常子类型、{@code datetime(3)}
 * 在真 MySQL 上的截断行为、大批量时的执行计划——这些是数据库自己的语义，不是本类要钉的<b>我们的代码</b>的形状。
 */
class JdbcOutboxStoreTest {

    /** 全在整毫秒上：H2 与 MySQL 的 datetime(3) 都是毫秒精度，测试数据不靠四舍五入活着。 */
    private static final Instant T0 = Instant.ofEpochMilli(1_700_000_000_000L);
    /** 与 T0 相差一小时的第二个时间点，用于"到期/未到期""覆盖/未覆盖"这类成对断言。 */
    private static final Instant T1 = T0.plusSeconds(3600);
    private static final AtomicInteger DATABASES = new AtomicInteger();

    private CountingDataSource dataSource;
    private JdbcTemplate jdbc;
    private CountingTransactions claimTransaction;
    private JdbcOutboxStore store;
    private String outboxTable;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:outbox-" + DATABASES.incrementAndGet() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        DriverManagerDataSource backing = new DriverManagerDataSource(url);
        backing.setDriverClassName("org.h2.Driver");
        dataSource = new CountingDataSource(backing);
        jdbc = new JdbcTemplate(dataSource);
        claimTransaction = new CountingTransactions(
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        store = new JdbcOutboxStore(dataSource, claimTransaction, DdlReference.OUTBOX_TABLE);
        outboxTable = DdlReference.OUTBOX_TABLE;
        createReferenceSchema(outboxTable);
    }

    // ---------------------------------------------------------------- save

    @Test
    void should_insert_every_record_column_and_nothing_else() {
        store.save(pending("e1", T0));

        assertThat(text("e1", "event_id")).isEqualTo("e1");
        assertThat(text("e1", "event_type")).isEqualTo("org.deep361.sample.OrderCreated");
        assertThat(text("e1", "aggregate_type")).isEqualTo("org.deep361.sample.Order");
        assertThat(text("e1", "aggregate_id")).isEqualTo("order-e1");
        assertThat(text("e1", "trace_id")).isEqualTo("trace-e1");
        assertThat(text("e1", "channel")).isEqualTo("order-events");
        assertThat(text("e1", "payload")).isEqualTo("{\"n\":1}");
        assertThat(text("e1", "status")).isEqualTo("PENDING");
        assertThat(number("e1", "retry_count")).isEqualTo(0);
        assertThat(instant("e1", "occurred_at")).isEqualTo(T0.minusSeconds(1));
        assertThat(instant("e1", "next_retry_at")).isEqualTo(T0);
        assertThat(instant("e1", "created_at")).isEqualTo(T0);
        assertThat(instant("e1", "last_error")).isNull();
        assertThat(instant("e1", DdlReference.LEASE_COLUMN)).isNull();
    }

    @Test
    void should_share_the_callers_transaction_so_a_rollback_erases_the_record() {
        TransactionTemplate outer = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        assertThatThrownBy(() -> outer.execute(status -> {
            store.save(pending("kept", T0));
            store.save(pending("rolled-back", T1));
            throw new IllegalStateException("业务失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(allEventIds()).isEmpty();
    }

    @Test
    void should_never_open_a_transaction_of_its_own_when_saving_or_updating() {
        TransactionTemplate outer = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        outer.execute(status -> {
            dataSource.resetConnections();
            store.save(pending("e1", T0));
            store.update(pending("e1", T0).dispatching().retrying(T1, "boom"));
            return null;
        });

        assertThat(dataSource.connections()).describedAs(
                "外层已有一个事务，store 只是复用它：一次连接都不该额外要").isZero();
        assertThat(text("e1", "status")).isEqualTo("PENDING");
        assertThat(text("e1", "last_error")).isEqualTo("boom");
    }

    @Test
    void should_refuse_to_save_a_record_that_is_not_pending() {
        OutboxRecord dispatching = pending("e1", T0).dispatching();

        assertThatThrownBy(() -> store.save(dispatching))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("只接受 PENDING 记录")
                .hasMessageContaining("DISPATCHING")
                .hasMessageContaining("e1");
        assertThatThrownBy(() -> store.save(dispatching.dispatched()))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("DISPATCHED");
        assertThatThrownBy(() -> store.save(dispatching.dead("no")))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("DEAD");

        assertThat(rowCount()).isZero();
    }

    @Test
    void should_surface_a_duplicate_event_id_as_a_data_integrity_failure_and_keep_the_original_row() {
        store.save(pending("e1", T0));

        assertThatThrownBy(() -> store.save(pending("e1", T1)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(rowCount()).isEqualTo(1);
        assertThat(instant("e1", "created_at")).isEqualTo(T0);
    }

    @Test
    void should_hint_the_reference_ddl_when_the_first_statement_hits_a_missing_table() {
        jdbc.execute("drop table myddd_outbox");

        assertThatThrownBy(() -> store.save(pending("e1", T0)))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining(DdlReference.OUTBOX_DDL)
                .hasMessageContaining("myddd.persistence.outbox.table")
                .hasCauseInstanceOf(BadSqlGrammarException.class);
    }

    @Test
    void should_report_a_plain_grammar_error_once_a_statement_has_succeeded() {
        store.save(pending("e1", T0));
        jdbc.execute("drop table myddd_outbox");

        assertThatThrownBy(() -> store.save(pending("e2", T1)))
                .isInstanceOf(BadSqlGrammarException.class)
                .hasMessageNotContaining("参考建表脚本");
    }

    // ------------------------------------------------------------- claim

    @Test
    void should_only_claim_pending_records_whose_next_retry_at_has_arrived() {
        store.save(pending("due", T0));
        store.save(withNextRetry(pending("later", T0), T1));

        List<OutboxRecord> claimed = store.claimDispatchable(T0, T0.plusSeconds(30), 10);

        assertThat(claimed).extracting(OutboxRecord::eventId).containsExactly("due");
    }

    @Test
    void should_reclaim_a_dispatching_record_only_after_its_lease_expired() {
        store.save(pending("alive", T0));
        store.save(pending("crashed", T0));
        // 两批各认领一条：同一 created_at 下按 event_id 先进先出，所以 alive 拿到长租约、crashed 拿到短租约
        assertThat(store.claimDispatchable(T0, T0.plusSeconds(600), 1)).extracting(OutboxRecord::eventId)
                .containsExactly("alive");
        assertThat(store.claimDispatchable(T0, T0.plusSeconds(30), 1)).extracting(OutboxRecord::eventId)
                .containsExactly("crashed");

        assertThat(store.claimDispatchable(T0.plusSeconds(10), T0.plusSeconds(90), 10))
                .as("两条租约都还没到期，谁都不该被再次认领").isEmpty();
        assertThat(store.claimDispatchable(T0.plusSeconds(30), T0.plusSeconds(90), 10))
                .as("now 正好等于 crashed 的租约到期时刻：谓词是严格小于，这一轮还不该放行").isEmpty();

        List<OutboxRecord> reclaimed = store.claimDispatchable(T0.plusSeconds(61), T0.plusSeconds(120), 10);
        assertThat(reclaimed).extracting(OutboxRecord::eventId).containsExactly("crashed");
        assertThat(instant("crashed", DdlReference.LEASE_COLUMN)).isEqualTo(T0.plusSeconds(120));
        assertThat(instant("alive", DdlReference.LEASE_COLUMN)).isEqualTo(T0.plusSeconds(600));
    }

    /**
     * 比较并交换认领<b>只动它点名的那一行</b>：同一时刻另有一条"过期租约"的记录在场，也不许被顺手带走。
     *
     * <p>这条钉的是认领谓词上那对最外层的括号。SQL 里 {@code and} 比 {@code or} 绑定更紧，那句一旦写成
     * {@code where event_id = ? and (A) or (B)}，{@code (B)} 这一支就没有任何 {@code event_id} 约束了：
     * 一次 {@code update} 改到两行 → 返回值 2 让本行反倒判成"没抢到"（这一轮谁都投不出去），
     * 而隔壁那条的旧租约被换成新的（症状是崩溃持有者的记录永远卡在 DISPATCHING，且查不到谁改的）。
     * 两个症状各自可观测，所以下面三条断言一句都不多余。
     */
    @Test
    void should_cas_claim_only_the_row_it_named_and_not_a_neighbour_with_an_expired_lease() {
        store.save(pending("m", T0));
        store.save(pending("z", T0));
        // 手工把 z 造成"持有者已崩溃"的样子：DISPATCHING、租约早已到期。它同样是候选，
        // 但 created_at 相同而 m 的 event_id 在前，limit=1  ⇒ 这一轮点名的必须是 m。
        jdbc.update("update " + outboxTable + " set status = ?, " + DdlReference.LEASE_COLUMN + " = ?"
                + " where event_id = ?", OutboxStatus.DISPATCHING.name(), Timestamp.from(T0.plusSeconds(30)), "z");

        List<OutboxRecord> claimed = store.claimDispatchable(T0.plusSeconds(61), T0.plusSeconds(120), 1);

        assertThat(claimed).extracting(OutboxRecord::eventId).containsExactly("m");
        assertThat(instant("m", DdlReference.LEASE_COLUMN)).isEqualTo(T0.plusSeconds(120));
        assertThat(text("z", "status")).as("没被点名的记录不许被顺手认领").isEqualTo("DISPATCHING");
        assertThat(instant("z", DdlReference.LEASE_COLUMN)).as("z 的旧租约是它的墓碑：续上新租约就等于让它永远卡着")
                .isEqualTo(T0.plusSeconds(30));
    }

    @Test
    void should_claim_in_created_at_then_event_id_order_and_stop_at_the_limit() {
        // 两个方向各自独立可杀：a 最晚入库但 next_retry_at 被拨到 now ⇒ 排序主键换成 event_id 时第一条变成 a；
        // limit=2 ⇒ 第三条必须仍是 PENDING。（z/m 的平局次序钉不住本条：H2 与 InnoDB 的扫描次序本来就是
        // event_id 升序，丢掉次级排序键结果不变——那一维由
        // {@link #should_emit_a_totally_ordered_candidate_and_bind_now_into_the_cas_predicate()} 在 SQL 文本上钉。）
        store.save(pending("z", T0));
        store.save(pending("m", T0));
        store.save(withNextRetry(pending("a", T0.plusSeconds(1)), T0));

        List<OutboxRecord> claimed = store.claimDispatchable(T0, T0.plusSeconds(30), 2);

        assertThat(claimed).extracting(OutboxRecord::eventId).containsExactly("m", "z");
        assertThat(text("a", "status")).isEqualTo("PENDING");
    }

    /**
     * 候选集的<b>全序</b>与认领语句<b>绑进去的时刻</b>，是两条在数据上演不出差别的东西：
     * <ul>
     *   <li>次级排序键：H2 与 InnoDB 都按主键 {@code event_id} 组织数据，平局时的天然次序恰好就是
     *       {@code event_id} 升序，所以"丢掉 {@code , event_id}"在两个引擎上都翻不出错——但换到堆表
     *       （PostgreSQL、SQL Server）或走别的索引扫描时平局次序不受控，翻页会重复或漏行。
     *       变异扫描实测过这一条：注入的那一遍本类 25 条用例全绿（S12 还不存在），补上文本钉子后才杀掉。</li>
     *   <li>比较并交换谓词里的 {@code next_retry_at <= ?}：候选集已按同一个 {@code now} 过滤过一遍，
     *       所以绑成 {@code leaseUntil} 在单线程下永远多算不进——它只在"选中之后、改写之前有人把重试拨到
     *       将来"那次竞争里才露脸，而那正是这条谓词存在的理由。</li>
     *   <li>候选集谓词那两条比较绑的时刻。把它们任一处绑成 {@code leaseUntil}，{@code claimDispatchable}
     *       的返回值照样不变——多选进来的行会在比较并交换那一步被拒。露脸的是 {@code limit}：一条还没到期的
     *       行占掉名额，本来这一轮认领得到的行就被挤出去了。变异扫描实测过这一条（租约腿绑成
     *       {@code leaseUntil}，本类 26 条全绿），所以四个位置得逐个钉。</li>
     * </ul>
     *
     * <p>于是这一条钉的是<b>发出去的 SQL 文本与它逐个位置的绑定</b>：文本里必须有全序，租约列绑
     * {@code leaseUntil}，四处"是否到期/是否过期"的比较（候选集两处、认领语句两处）一律绑 {@code now}。与
     * {@code JdbcOutboxDeadLetterStoreTest} 里"子句文本 + 绑定顺序成对钉"是同一手法。
     */
    @Test
    void should_emit_a_totally_ordered_candidate_and_bind_now_into_the_cas_predicate() {
        store.save(pending("e1", T0));
        List<String> statements = new ArrayList<>();
        Map<String, Map<Integer, Object>> bindings = new LinkedHashMap<>();
        DataSource recording = recording(dataSource, statements, bindings);
        JdbcOutboxStore recordingStore = new JdbcOutboxStore(recording,
                new TransactionTemplate(new DataSourceTransactionManager(recording)), outboxTable);

        Instant leaseUntil = T0.plusSeconds(30);
        assertThat(recordingStore.claimDispatchable(T0, leaseUntil, 5)).hasSize(1);

        assertThat(statements).as("候选集查询必须带全序：只按 created_at 排是半序")
                .anyMatch(sql -> sql.startsWith("select") && sql.endsWith("order by created_at, event_id"));
        String claim = statements.stream()
                .filter(sql -> sql.startsWith("update") && sql.contains(DdlReference.LEASE_COLUMN + " = ? where"))
                .findFirst().orElseThrow(() -> new AssertionError("没发出比较并交换语句：" + statements));
        Map<Integer, Object> bound = bindings.get(claim);

        assertThat(bound.get(1)).isEqualTo(OutboxStatus.DISPATCHING.name());
        assertThat(bound.get(2)).as("租约列绑的才是租约到期时刻").isEqualTo(Timestamp.from(leaseUntil));
        assertThat(bound.get(3)).isEqualTo("e1");
        assertThat(bound.get(4)).isEqualTo(OutboxStatus.PENDING.name());
        assertThat(bound.get(5)).as("next_retry_at <= ? 绑 now：绑成 leaseUntil 等于把还没到期的行也算成可认领")
                .isEqualTo(Timestamp.from(T0));
        assertThat(bound.get(7)).as("dispatch_lease_until < ? 同样绑 now").isEqualTo(Timestamp.from(T0));

        Map<Integer, Object> candidate = bindings.get(statements.stream()
                .filter(sql -> sql.startsWith("select"))
                .findFirst().orElseThrow(() -> new AssertionError("没发出候选集查询：" + statements)));
        assertThat(candidate.get(1)).isEqualTo(OutboxStatus.PENDING.name());
        assertThat(candidate.get(2)).as("候选集的到期比较绑 now").isEqualTo(Timestamp.from(T0));
        assertThat(candidate.get(3)).isEqualTo(OutboxStatus.DISPATCHING.name());
        assertThat(candidate.get(4)).as("候选集的租约比较也绑 now：绑成 leaseUntil 会让还没到期的行占掉 limit 名额，"
                + "它在比较并交换那一步被拒，于是这一轮本来认领得到的行被挤出去").isEqualTo(Timestamp.from(T0));
    }

    @Test
    void should_mark_claimed_rows_dispatching_and_write_the_lease() {
        Instant lease = T0.plusSeconds(45);
        store.save(pending("e1", T0));

        store.claimDispatchable(T0, lease, 10);

        assertThat(text("e1", "status")).isEqualTo("DISPATCHING");
        assertThat(instant("e1", DdlReference.LEASE_COLUMN)).isEqualTo(lease);
        assertThat(instant("e1", "next_retry_at")).isEqualTo(T0);
        assertThat(number("e1", "retry_count")).isZero();
    }

    @Test
    void should_return_records_already_marked_dispatching_and_faithful_to_the_row() {
        store.save(pending("e1", T0));
        store.update(pending("e1", T0).dispatching().retrying(T1, "第一次失败"));

        List<OutboxRecord> claimed = store.claimDispatchable(T1, T1.plusSeconds(30), 10);

        assertThat(claimed).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(OutboxStatus.DISPATCHING);
            assertThat(record.eventId()).isEqualTo("e1");
            assertThat(record.eventType()).isEqualTo("org.deep361.sample.OrderCreated");
            assertThat(record.aggregateId()).isEqualTo("order-e1");
            assertThat(record.traceId()).isEqualTo("trace-e1");
            assertThat(record.channel()).isEqualTo("order-events");
            assertThat(record.payload()).isEqualTo("{\"n\":1}");
            assertThat(record.retryCount()).isEqualTo(1);
            assertThat(record.nextRetryAt()).isEqualTo(T1);
            assertThat(record.lastError()).isEqualTo("第一次失败");
            assertThat(record.occurredAt()).isEqualTo(T0.minusSeconds(1));
            assertThat(record.createdAt()).isEqualTo(T0);
        });
    }

    @Test
    void should_keep_the_coordinate_columns_verbatim_across_a_write_that_has_no_trace_id() {
        store.save(noTracePending("e1", T0));

        List<OutboxRecord> claimed = store.claimDispatchable(T0, T0.plusSeconds(30), 10);

        assertThat(claimed).singleElement().satisfies(record -> {
            assertThat(record.traceId()).isNull();
            assertThat(record.lastError()).isNull();
            assertThat(record.retryCount()).isZero();
        });
        assertThat(instant("e1", "trace_id")).isNull();
    }

    @Test
    void should_return_nothing_and_touch_nothing_when_no_record_is_due() {
        store.save(withNextRetry(pending("e1", T0), T1));

        assertThat(store.claimDispatchable(T0, T0.plusSeconds(30), 10)).isEmpty();

        assertThat(claimTransaction.executions()).isEqualTo(1);
        assertThat(text("e1", "status")).isEqualTo("PENDING");
        assertThat(instant("e1", DdlReference.LEASE_COLUMN)).isNull();
    }

    @Test
    void should_run_select_and_claim_update_in_exactly_one_transaction() {
        store.save(pending("e1", T0));

        dataSource.resetConnections();
        store.claimDispatchable(T0, T0.plusSeconds(30), 10);

        assertThat(claimTransaction.executions()).describedAs("认领的两句 SQL 必须共用一个事务").isEqualTo(1);
        assertThat(dataSource.connections()).describedAs(
                "连接只应由事务管理器要一次；store 自己 getConnection 会让两句落在不同连接上").isEqualTo(1);
    }

    @Test
    void should_reject_claim_arguments_that_cannot_produce_a_usable_lease() {
        assertThatThrownBy(() -> store.claimDispatchable(null, T1, 10))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("认领的当前时间 now 不能为 null");
        assertThatThrownBy(() -> store.claimDispatchable(T0, null, 10))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("认领的租约到期时间 leaseUntil 不能为 null");
        assertThatThrownBy(() -> store.claimDispatchable(T0, T1, 0))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("limit 必须 ≥ 1")
                .hasMessageContaining("实际 0");
        assertThatThrownBy(() -> store.claimDispatchable(T1, T0, 10))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("必须晚于 now");
        // 相等同样是"没有租约"：这一句钉死守卫必须是严格晚于，而不是不早于
        assertThatThrownBy(() -> store.claimDispatchable(T0, T0, 10))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("必须晚于 now");
        assertThat(claimTransaction.executions()).describedAs("守卫必须在进事务之前失败").isZero();
    }

    // ------------------------------------------------------------- update

    @Test
    void should_overwrite_the_mutable_columns_clear_the_lease_and_leave_the_rest_alone() {
        store.save(pending("e1", T0));
        store.claimDispatchable(T0, T0.plusSeconds(30), 10);

        store.update(pending("e1", T0).dispatching().retrying(T1, "第二次失败"));

        assertThat(text("e1", "status")).isEqualTo("PENDING");
        assertThat(number("e1", "retry_count")).isEqualTo(1);
        assertThat(instant("e1", "next_retry_at")).isEqualTo(T1);
        assertThat(text("e1", "last_error")).isEqualTo("第二次失败");
        assertThat(instant("e1", DdlReference.LEASE_COLUMN)).isNull();
        assertThat(text("e1", "event_type")).isEqualTo("org.deep361.sample.OrderCreated");
        assertThat(text("e1", "aggregate_type")).isEqualTo("org.deep361.sample.Order");
        assertThat(text("e1", "aggregate_id")).isEqualTo("order-e1");
        assertThat(text("e1", "trace_id")).isEqualTo("trace-e1");
        assertThat(text("e1", "channel")).isEqualTo("order-events");
        assertThat(text("e1", "payload")).isEqualTo("{\"n\":1}");
        assertThat(instant("e1", "occurred_at")).isEqualTo(T0.minusSeconds(1));
        assertThat(instant("e1", "created_at")).isEqualTo(T0);
    }

    @Test
    void should_fail_an_update_that_matches_no_row() {
        assertThatThrownBy(() -> store.update(pending("ghost", T0).dispatching()))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("影响了 0 行")
                .hasMessageContaining("ghost")
                .hasMessageContaining(outboxTable);

        assertThat(rowCount()).isZero();
    }

    @Test
    void should_truncate_an_over_long_last_error_instead_of_failing_the_write() {
        String longError = "失".repeat(JdbcOutboxStore.LAST_ERROR_MAX_LENGTH + 500);
        store.save(pending("e1", T0));
        store.claimDispatchable(T0, T0.plusSeconds(30), 10);

        store.update(pending("e1", T0).dispatching().retrying(T1, longError));

        assertThat(text("e1", "last_error")).hasSize(JdbcOutboxStore.LAST_ERROR_MAX_LENGTH)
                .isEqualTo(longError.substring(0, JdbcOutboxStore.LAST_ERROR_MAX_LENGTH));
    }

    @Test
    void should_report_a_row_whose_required_timestamp_column_is_null_through_the_record_guard() {
        // 参考 DDL 里 created_at 是 not null，本框架的写路径也永远绑非空值；这一条覆盖的是"表被放宽过约束、
        // 行被别的工具写进来"的场景：存储不许把它静默映射成 null，也不许自己造一套消息——
        // OutboxRecord 的构造守卫才是这条约束的真源，这里只负责把 NULL 原样递过去。
        store.save(pending("e1", T0));
        jdbc.execute("alter table myddd_outbox alter column created_at drop not null");
        jdbc.update("update myddd_outbox set created_at = null where event_id = ?", "e1");

        assertThatThrownBy(() -> store.claimDispatchable(T0, T0.plusSeconds(30), 10))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("createdAt 不能为 null");
    }

    @Test
    void should_keep_the_truncation_limit_equal_to_the_column_width_in_the_reference_ddl() {
        // 截断长度与列宽是同一件事的两处写法：改宽了列不改截断，写入照样在库侧被砍且没人报警。
        Matcher width = Pattern.compile("last_error\\s+varchar\\((\\d+)\\)").matcher(
                DdlReference.read(DdlReference.OUTBOX_DDL));

        assertThat(width.find()).as("参考 DDL 里 last_error 必须是 varchar(N)").isTrue();
        assertThat(Integer.parseInt(width.group(1))).isEqualTo(JdbcOutboxStore.LAST_ERROR_MAX_LENGTH);
    }

    @Test
    void should_participate_in_the_callers_transaction_when_updating() {
        store.save(pending("e1", T0));
        store.claimDispatchable(T0, T0.plusSeconds(30), 10);
        TransactionTemplate outer = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        assertThatThrownBy(() -> outer.execute(status -> {
            store.update(pending("e1", T0).dispatching().dispatched());
            throw new IllegalStateException("投递后收尾失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(text("e1", "status")).isEqualTo("DISPATCHING");
    }

    // ------------------------------------------------------------ 构造守卫

    @Test
    void should_refuse_a_table_name_that_could_be_spliced_into_sql() {
        for (String bad : List.of("myddd outbox", "myddd_outbox; drop table x", "MYDDD_OUTBOX", "\"myddd_outbox\"",
                "myddd-outbox", "", "a".repeat(65))) {
            assertThatThrownBy(() -> new JdbcOutboxStore(dataSource, claimTransaction, bad))
                    .as("表名 %s 必须被拒绝", bad)
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("[a-z0-9_]{1,64}")
                    .hasMessageContaining(DdlReference.OUTBOX_DDL);
        }
        assertThat(rowCount()).isZero();
        assertThat(claimTransaction.executions()).isZero();
    }

    @Test
    void should_reject_null_collaborators_with_the_kernel_message() {
        assertThatThrownBy(() -> new JdbcOutboxStore(null, claimTransaction, DdlReference.OUTBOX_TABLE))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Outbox 存储的数据源不能为 null");
        assertThatThrownBy(() -> new JdbcOutboxStore(dataSource, null, DdlReference.OUTBOX_TABLE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("事务执行器");
        assertThatThrownBy(() -> new JdbcOutboxStore(dataSource, claimTransaction, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Outbox 表名不能为 null");
    }

    // ------------------------------------------------------------- 夹具

    private void createReferenceSchema(String table) {
        String ddl = DdlReference.read(DdlReference.OUTBOX_DDL);
        if (!table.equals(DdlReference.OUTBOX_TABLE)) {
            ddl = ddl.replace(DdlReference.OUTBOX_TABLE, table);
        }
        Arrays.stream(ddl.split(";")).map(String::trim).filter(statement -> !statement.isEmpty())
                .forEach(jdbc::execute);
    }

    private static OutboxRecord pending(String eventId, Instant createdAt) {
        return OutboxRecord.pending(eventId, "org.deep361.sample.OrderCreated", "org.deep361.sample.Order",
                "order-" + eventId, createdAt.minusSeconds(1), "trace-" + eventId, "order-events",
                "{\"n\":1}", createdAt);
    }

    private static OutboxRecord noTracePending(String eventId, Instant createdAt) {
        return OutboxRecord.pending(eventId, "org.deep361.sample.OrderCreated", "org.deep361.sample.Order",
                "order-" + eventId, createdAt.minusSeconds(1), null, "order-events", "{\"n\":1}", createdAt);
    }

    private static OutboxRecord withNextRetry(OutboxRecord record, Instant nextRetryAt) {
        return new OutboxRecord(record.eventId(), record.eventType(), record.aggregateType(), record.aggregateId(),
                record.traceId(), record.occurredAt(), record.channel(), record.payload(), record.status(),
                record.retryCount(), nextRetryAt, record.lastError(), record.createdAt());
    }

    private int rowCount() {
        return jdbc.query("select count(*) from " + outboxTable, (rs, n) -> rs.getInt(1)).get(0);
    }

    private List<String> allEventIds() {
        return jdbc.query("select event_id from " + outboxTable, (rs, n) -> rs.getString(1));
    }

    private String text(String eventId, String column) {
        return read(eventId, column, rs -> first(rs) ? rs.getString(1) : null);
    }

    private Integer number(String eventId, String column) {
        return read(eventId, column, rs -> first(rs) ? rs.getObject(1) == null ? null : rs.getInt(1) : null);
    }

    private Instant instant(String eventId, String column) {
        return read(eventId, column, rs -> {
            if (!first(rs)) {
                return null;
            }
            Timestamp value = rs.getTimestamp(1);
            return value == null ? null : value.toInstant();
        });
    }

    /** ResultSetExtractor 的游标停在第一行之前，任何读取都要先推进。 */
    private static boolean first(ResultSet rs) throws SQLException {
        return rs.next();
    }

    private <T> T read(String eventId, String column, ResultSetExtractor<T> extractor) {
        return jdbc.query(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "select " + column + " from " + outboxTable + " where event_id = ?");
            ps.setString(1, eventId);
            return ps;
        }, extractor);
    }

    /**
     * 语句级录像件：记下每条 {@code prepareStatement} 的 SQL 文本，以及该语句逐个位置绑进去的值。
     *
     * <p>为什么需要它：本类的其余用例全部从"库里的行"读结果，而候选集的全序、比较并交换谓词的绑定
     * 这两件事在 H2 与 InnoDB 上都翻不出错行（见
     * {@link #should_emit_a_totally_ordered_candidate_and_bind_now_into_the_cas_predicate()} 的说明）。
     * 它们是要发给<b>任意</b>目标库的交付物，所以钉的是发出去的东西本身。
     *
     * <p>只记 {@code setString/setInt/setLong/setTimestamp/setObject} 这一组：{@code setMaxRows} 的入参也是
     * "一个整数 + 一个值"，混进来会让位置账对不上。
     */
    private static DataSource recording(DataSource delegate, List<String> statements,
            Map<String, Map<Integer, Object>> bindings) {
        return (DataSource) Proxy.newProxyInstance(JdbcOutboxStoreTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    Object result = forward(delegate, method, args);
                    return result instanceof Connection connection
                            ? connectionRecording(connection, statements, bindings) : result;
                });
    }

    private static Connection connectionRecording(Connection delegate, List<String> statements,
            Map<String, Map<Integer, Object>> bindings) {
        return (Connection) Proxy.newProxyInstance(JdbcOutboxStoreTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    Object result = forward(delegate, method, args);
                    boolean prepared = "prepareStatement".equals(method.getName()) && result instanceof PreparedStatement;
                    if (prepared) {
                        String sql = (String) args[0];
                        statements.add(sql);
                        return statementRecording(sql, (PreparedStatement) result, bindings);
                    }
                    return result;
                });
    }

    private static PreparedStatement statementRecording(String sql, PreparedStatement delegate,
            Map<String, Map<Integer, Object>> bindings) {
        Map<Integer, Object> bound = bindings.computeIfAbsent(sql, key -> new LinkedHashMap<>());
        return (PreparedStatement) Proxy.newProxyInstance(JdbcOutboxStoreTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                    if (BINDINGS.contains(method.getName()) && args != null && args[0] instanceof Integer position) {
                        bound.put(position, args[1]);
                    }
                    return forward(delegate, method, args);
                });
    }

    private static final Set<String> BINDINGS = Set.of("setString", "setInt", "setLong", "setTimestamp", "setObject");

    /** 转发一次调用，并把反射包装还原成原始异常：代理不该改变异常的形状。 */
    private static Object forward(Object delegate, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }

    /**
     * 计数装饰器：只加两件观测——{@code getConnection()} 被谁要了几次、事务回调跑了几次。
     * 有活动事务时 {@code DataSourceUtils} 不再向数据源要连接，所以这两个计数就是"参与当前事务"的可观测证据。
     */
    private static final class CountingDataSource implements DataSource {

        private final DataSource delegate;
        private final AtomicInteger connections = new AtomicInteger();

        CountingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        void resetConnections() {
            connections.set(0);
        }

        int connections() {
            return connections.get();
        }

        @Override
        public Connection getConnection() throws SQLException {
            connections.incrementAndGet();
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            connections.incrementAndGet();
            return delegate.getConnection(username, password);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }

    private static final class CountingTransactions implements TransactionOperations {

        private final TransactionOperations delegate;
        private final AtomicInteger executions = new AtomicInteger();

        CountingTransactions(TransactionOperations delegate) {
            this.delegate = delegate;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            executions.incrementAndGet();
            return delegate.execute(action);
        }

        int executions() {
            return executions.get();
        }
    }
}
