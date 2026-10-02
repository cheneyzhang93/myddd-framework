package org.deep361.myddd.persistence.outbox;

import org.deep361.myddd.domain.outbox.OutboxRecord;
import org.deep361.myddd.domain.outbox.OutboxStatus;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.common.page.PageQuery;
import org.deep361.myddd.kernel.common.page.PageResult;
import org.deep361.myddd.persistence.ddl.DdlReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JdbcOutboxDeadLetterStore} 的行为验收：死信分页查询与重放的 SQL 语义。
 *
 * <p><b>与写侧同一套地基</b>：库是 H2 {@code MODE=MySQL}，表结构是交付的那份参考 DDL 原文
 * （{@link DdlReference#OUTBOX_DDL}），没有第二份 schema。本类钉的是<b>读侧代码</b>的形状：状态过滤、时间窗边界、
 * 排序与翻页、count 与 select 的事务一致性、重放的条件谓词、以及各条守卫。
 *
 * <p><b>这里不证明的</b>：{@code in (...) } 大批量的执行计划、优化器到底有没有用上 {@code created_at} 上的索引、
 * {@code datetime(3)} 在真 MySQL 上的精度截断——这些是数据库自己的语义。"换一种数据库还能不能正确翻页"这件事
 * <b>本类证得了</b>：子句由连接的元数据决定，两条分支（标准形态与 MySQL 形态）各自在真 H2 上翻过页，
 * 见 {@code should_page_with_the_ansi_clause_when_the_database_does_not_report_mysql} 与它的对偶；
 * 而"到底发出去的是哪一种子句"由 {@code should_send_each_databases_own_clause_text_in_the_paged_statement}
 * 钉住文本——H2 两种形态都认，只看行数判不出选错支，所以那条不靠翻页结果。
 *
 * <p><b>两处观测件为什么这么搭</b>：
 * <ul>
 *   <li>连接计数用 {@link Proxy} 包 {@link DataSource}（它本来就是接口）：一次翻页只该要一次连接，
 *       这是"count 与 select 同事务"最直接的物证，比数事务回调次数强。</li>
 *   <li>夹具写入走字面 SQL（列名逐个写死），不走被测代码：否则"读侧漏了 {@code last_error}"这一类变异
 *       会被写入侧的同一个漏洞一起掩盖掉。</li>
 * </ul>
 */
class JdbcOutboxDeadLetterStoreTest {

    /** 每个用例一个库：{@code DB_CLOSE_DELAY=-1} 的内存库活到 JVM 结束，共名会让上一个用例的表渗进来。 */
    private static final AtomicInteger DATABASES = new AtomicInteger();

    /** 全部落在整毫秒上：datetime(3) 的读写不靠四舍五入活着。 */
    private static final Instant T0 = Instant.ofEpochMilli(1_700_000_000_000L);
    private static final int DEFAULT_LIMIT = 200;

    private DriverManagerDataSource backing;
    private AtomicInteger connections;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private CountingTransactions readTransaction;
    private JdbcOutboxDeadLetterStore store;
    private String table;

    @BeforeEach
    void setUp() {
        backing = new DriverManagerDataSource("jdbc:h2:mem:deadletter-" + DATABASES.incrementAndGet()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        backing.setDriverClassName("org.h2.Driver");
        connections = new AtomicInteger();
        dataSource = counting(backing, connections);
        // 夹具的读写走未被装饰的数据源：连接计数只反映被测代码要了几次连接
        jdbc = new JdbcTemplate(backing);
        readTransaction = new CountingTransactions(
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        table = DdlReference.OUTBOX_TABLE;
        store = newStore(DEFAULT_LIMIT);
        createReferenceSchema(table);
    }

    // ------------------------------------------------------------- findDead

    @Test
    void should_report_only_the_dead_letters_inside_the_window() {
        insert("in-1", OutboxStatus.DEAD, T0);
        insert("in-2", OutboxStatus.DEAD, T0.plusSeconds(60));
        insert("in-3", OutboxStatus.DEAD, T0.plusSeconds(120));
        insert("before", OutboxStatus.DEAD, T0.minusSeconds(1));
        insert("after", OutboxStatus.DEAD, T0.plusSeconds(181));
        insert("pending", OutboxStatus.PENDING, T0.plusSeconds(30));
        insert("dispatching", OutboxStatus.DISPATCHING, T0.plusSeconds(30));
        insert("dispatched", OutboxStatus.DISPATCHED, T0.plusSeconds(30));

        PageResult<OutboxRecord> page = store.findDead(T0, T0.plusSeconds(181), PageQuery.of(1, 10));

        assertThat(ids(page)).containsExactlyInAnyOrder("in-1", "in-2", "in-3");
        assertThat(page.total()).as("总数只数得上窗口的死信，不受其他状态干扰").isEqualTo(3L);
    }

    @Test
    void should_include_the_lower_bound_and_exclude_the_upper_bound() {
        insert("at-from", OutboxStatus.DEAD, T0);
        insert("at-to", OutboxStatus.DEAD, T0.plusSeconds(60));

        assertThat(ids(store.findDead(T0, T0.plusSeconds(60), PageQuery.of(1, 10))))
                .as("下界含、上界不含：相邻两页的时间窗首尾相接而不重叠一条记录")
                .containsExactly("at-from");
    }

    @Test
    void should_drop_a_null_bound_instead_of_filtering_everything_out() {
        insert("old", OutboxStatus.DEAD, T0.minusSeconds(3600));
        insert("young", OutboxStatus.DEAD, T0.plusSeconds(3600));

        assertThat(ids(store.findDead(null, null, PageQuery.of(1, 10)))).containsExactly("young", "old");
        assertThat(ids(store.findDead(null, T0, PageQuery.of(1, 10)))).containsExactly("old");
        assertThat(ids(store.findDead(T0, null, PageQuery.of(1, 10)))).containsExactly("young");
    }

    @Test
    void should_list_the_newest_dead_letter_first_and_break_ties_by_event_id() {
        insert("a-older", OutboxStatus.DEAD, T0);
        insert("b-middle", OutboxStatus.DEAD, T0.plusSeconds(60));
        // 同一时刻三条：只有 event_id 参与次级排序，翻页才有全序（不写 tie-break 时 MySQL 与 H2 可以各给一个顺序）
        insert("c-1", OutboxStatus.DEAD, T0.plusSeconds(120));
        insert("c-2", OutboxStatus.DEAD, T0.plusSeconds(120));
        insert("c-3", OutboxStatus.DEAD, T0.plusSeconds(120));

        assertThat(ids(store.findDead(null, null, PageQuery.of(1, 10))))
                .containsExactly("c-3", "c-2", "c-1", "b-middle", "a-older");
    }

    @Test
    void should_split_the_same_window_across_pages_without_overlap_or_gaps() {
        for (int i = 1; i <= 5; i++) {
            insert("d" + i, OutboxStatus.DEAD, T0.plusSeconds(i * 10L));
        }

        List<String> first = ids(store.findDead(null, null, PageQuery.of(1, 2)));
        List<String> second = ids(store.findDead(null, null, PageQuery.of(2, 2)));
        List<String> third = ids(store.findDead(null, null, PageQuery.of(3, 2)));

        assertThat(first).containsExactly("d5", "d4");
        assertThat(second).containsExactly("d3", "d2");
        assertThat(third).containsExactly("d1");
        List<String> paged = new ArrayList<>(first);
        paged.addAll(second);
        paged.addAll(third);
        assertThat(paged).as("三页拼起来不多不少：offset 偏一格就会同时表现为重复与缺行")
                .containsExactlyInAnyOrder("d1", "d2", "d3", "d4", "d5");
        assertThat(store.findDead(null, null, PageQuery.of(1, 2)).total()).isEqualTo(5L);
    }

    @Test
    void should_keep_the_total_visible_when_the_page_falls_past_the_end() {
        insert("d1", OutboxStatus.DEAD, T0);
        insert("d2", OutboxStatus.DEAD, T0.plusSeconds(1));
        insert("d3", OutboxStatus.DEAD, T0.plusSeconds(2));

        PageResult<OutboxRecord> beyond = store.findDead(null, null, PageQuery.of(9, 2));

        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.total()).as("最后一页翻过去仍然是 3 条，不是 0 条——界面上的\"共 3 条\"不许随翻页消失")
                .isEqualTo(3L);
        assertThat(beyond.pages()).isEqualTo(2L);
    }

    @Test
    void should_saturate_the_offset_of_an_absurd_page_number_instead_of_going_negative() {
        insert("d1", OutboxStatus.DEAD, T0);

        PageResult<OutboxRecord> page = store.findDead(null, null, PageQuery.of(Long.MAX_VALUE, 10));

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isEqualTo(1L);
    }

    @Test
    void should_cap_the_page_size_at_the_configured_limit_and_the_kernels_own() {
        for (int i = 1; i <= 7; i++) {
            insert("d" + i, OutboxStatus.DEAD, T0.plusSeconds(i));
        }

        // PageQuery 自己把页大小压到 500（内核的上限），配置的上限再压一道：两道天花板取小的那个
        store = newStore(3);
        PageResult<OutboxRecord> capped = store.findDead(null, null, PageQuery.of(1, 99999));

        assertThat(capped.pageSize()).as("页大小取\"请求值 / 内核上限 / 配置上限\"三者最小").isEqualTo(3L);
        assertThat(capped.items()).hasSize(3);
        assertThat(capped.total()).isEqualTo(7L);
        // 请求值同样压顶：offset 必须由压顶后的页大小算出，否则第二页会与第一页错位
        assertThat(ids(store.findDead(null, null, PageQuery.of(2, 99999)))).containsExactly("d4", "d3", "d2")
                .doesNotContainAnyElementsOf(ids(capped));

        store = newStore(1000);
        assertThat(store.findDead(null, null, PageQuery.of(1, 99999)).pageSize())
                .as("配置上限高于 500 时，内核的 500 生效：offset 与 limit 用的是同一个页大小，否则第二页会重叠第一页")
                .isEqualTo(500L);
    }

    /**
     * 分页子句由<b>目标库自己报的产品名</b>决定，两条分支各自在真库（H2）上翻出正确的那一页。
     *
     * <p>H2 的元数据报 {@code H2}，不在 MySQL 名单里，所以本用例走标准形态
     * {@code offset ? rows fetch next ? rows only}。它同时否掉了"把 MySQL 子句写死"这种实现：写死之后
     * 这句在 H2 上照样跑得通（H2 认得 {@code limit ? offset ?}），坏在参数顺序——把页大小当偏移量绑，
     * 症状正是翻页重叠或空页，而 SQL 合法、异常一个不抛。
     */
    @Test
    void should_page_with_the_ansi_clause_when_the_database_does_not_report_mysql() {
        for (int i = 1; i <= 5; i++) {
            insert("d" + i, OutboxStatus.DEAD, T0.plusSeconds(i));
        }

        connections.set(0);
        assertThat(ids(store.findDead(null, null, PageQuery.of(2, 2)))).containsExactly("d3", "d2");
        assertThat(store.findDead(null, null, PageQuery.of(3, 2)).items()).as("最后一页只剩一条").hasSize(1);
        assertThat(connections.get()).as("方言解析用的是执行这条语句的那个连接，一次翻页不多要连接").isEqualTo(2);
    }

    /**
     * 自称 MySQL 的连接拿到的另一套子句：{@code limit ? offset ?}，绑定顺序与标准形态相反。
     *
     * <p><b>代理包住的只有元数据里的产品名</b>：连接、语句、数据全是真 H2 的（H2 认得 MySQL 形态的子句），
     * 所以这条不是字符串对拍，而是让 MySQL 分支的子句文本与绑定顺序在库里真跑一遍翻页。
     */
    @Test
    void should_page_with_the_mysql_clause_when_the_database_reports_mysql() {
        for (int i = 1; i <= 5; i++) {
            insert("d" + i, OutboxStatus.DEAD, T0.plusSeconds(i));
        }
        AtomicInteger mysqlishConnections = new AtomicInteger();
        DataSource mysqlish = reportingAs(counting(backing, mysqlishConnections), "MySQL");
        JdbcOutboxDeadLetterStore mysqlishStore = new JdbcOutboxDeadLetterStore(mysqlish,
                new TransactionTemplate(new DataSourceTransactionManager(mysqlish)), table, DEFAULT_LIMIT);

        assertThat(ids(mysqlishStore.findDead(null, null, PageQuery.of(2, 2)))).containsExactly("d3", "d2");
        assertThat(ids(mysqlishStore.findDead(null, null, PageQuery.of(3, 2)))).as("最后一页只剩一条")
                .containsExactly("d1");
        assertThat(ids(mysqlishStore.findDead(null, null, PageQuery.of(1, 2)))).containsExactly("d5", "d4");
        assertThat(mysqlishConnections.get()).as("换方言不换连接账：三次翻页各一条").isEqualTo(3);
    }

    /**
     * 名单的两个端点各一枚钉子：MariaDB 报的名字里带 {@code MariaDB}（走非标准那一支），
     * 而**什么名字都报不出来**的连接（{@code getDatabaseProductName()} 返回 {@code null}）必须落到标准形态。
     *
     * <p>后者值得单独钉：判据是 {@code product.contains(...)}，实现里若不先挡 null 就是
     * {@code NullPointerException} 埋在"元数据拿不到"这条极少走的路上——真到那时用户换的是 PostgreSQL，
     * 报错却说不出任何与方言有关的话。认不出时用标准形态是这张表的**缺省**，不是巧合。
     */
    @Test
    void should_treat_mariadb_as_mysqlish_and_an_unnamed_database_as_ansi() {
        for (int i = 1; i <= 5; i++) {
            insert("d" + i, OutboxStatus.DEAD, T0.plusSeconds(i));
        }

        assertThat(ids(pagedOn(reportingAs(backing, "MariaDB")).findDead(null, null, PageQuery.of(2, 2))))
                .as("MariaDB 与 MySQL 同一支：它不认标准形态的 fetch next")
                .containsExactly("d3", "d2");
        assertThat(ids(pagedOn(reportingAs(backing, null)).findDead(null, null, PageQuery.of(2, 2))))
                .as("报不出产品名时走标准形态")
                .containsExactly("d3", "d2");
    }

    private JdbcOutboxDeadLetterStore pagedOn(DataSource dataSource) {
        return new JdbcOutboxDeadLetterStore(dataSource,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), table, DEFAULT_LIMIT);
    }

    /**
     * 翻到的行数证明不了子句<b>文本</b>选了哪一支：H2 两种形态都认，所以写死任一支都能翻出正确的页。
     * 这一条钉的是真正发给目标库的那串字符——它是这个类唯一的交付物，也是 {@code limit}/{@code offset}
     * 与 {@code offset}/{@code fetch next} 那两组绑定顺序（{@code should_page_with_the_mysql_clause…} 与
     * {@code should_page_with_the_ansi_clause…} 已各自在库里跑过）的搭档：文本与绑定顺序成对钉死，
     * 改子句忘了改绑定顺序会以测试失败收场。
     */
    @Test
    void should_send_each_databases_own_clause_text_in_the_paged_statement() {
        insert("d1", OutboxStatus.DEAD, T0);

        assertThat(clauseSentTo("MySQL")).endsWith(" limit ? offset ?");
        assertThat(clauseSentTo("MariaDB")).endsWith(" limit ? offset ?");
        assertThat(clauseSentTo("H2")).endsWith(" offset ? rows fetch next ? rows only");
        assertThat(clauseSentTo(null)).endsWith(" offset ? rows fetch next ? rows only");
    }

    private String clauseSentTo(String product) {
        List<String> statements = new ArrayList<>();
        pagedOn(reportingAs(backing, product, statements)).findDead(null, null, PageQuery.of(2, 1));
        List<String> paged = statements.stream().filter(sql -> sql.contains("order by")).toList();
        assertThat(paged).as("一次翻页只发一条带排序的分页语句（count 那条不带）").hasSize(1);
        return paged.get(0);
    }

    @Test
    void should_read_the_total_and_the_page_from_one_transaction_on_one_connection() {
        insert("d1", OutboxStatus.DEAD, T0);
        insert("d2", OutboxStatus.DEAD, T0.plusSeconds(1));

        PageResult<OutboxRecord> page = store.findDead(null, null, PageQuery.of(1, 1));

        assertThat(page.items()).hasSize(1);
        assertThat(page.total()).isEqualTo(2L);
        assertThat(readTransaction.executions()).as("count 与 select 在同一个事务回调里").isEqualTo(1);
        assertThat(connections.get()).as("两次读共用一条连接：自动提交的两条语句会各自要一条，那正是翻页翻乱的成因")
                .isEqualTo(1);
    }

    @Test
    void should_report_an_empty_page_without_a_second_query_when_nothing_matches() {
        insert("d1", OutboxStatus.PENDING, T0);

        PageResult<OutboxRecord> page = store.findDead(null, null, PageQuery.of(1, 10));

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(page.pageSize()).isEqualTo(10L);
        assertThat(connections.get()).isEqualTo(1);

        // 空页走的是短路分支，容易顺手写成"第 1 页"：界面停在第 7 页时这会让它弹回开头
        assertThat(store.findDead(null, null, PageQuery.of(7, 10)).pageNum())
                .as("总数为 0 时也要把请求页码原样回传").isEqualTo(7L);
    }

    @Test
    void should_return_every_record_column_including_the_failure_reason() {
        insert("d1", OutboxStatus.DEAD, T0);

        OutboxRecord dead = store.findDead(null, null, PageQuery.of(1, 10)).items().get(0);

        assertThat(dead).isEqualTo(new OutboxRecord("d1", "org.deep361.sample.OrderCancelled",
                "org.deep361.sample.Order", "order-d1", "trace-d1", T0.minusSeconds(1), "order-events",
                "{\"n\":1}", OutboxStatus.DEAD, 3, T0.plusSeconds(60), "boom-d1", T0));
        assertThat(dead.lastError()).as("运维查死信要看失败原因：读侧漏带 last_error 是最典型的\"两份行映射漂移\"")
                .isEqualTo("boom-d1");
    }

    @Test
    void should_refuse_a_window_whose_upper_bound_is_not_after_its_lower_bound() {
        for (Instant[] bounds : List.of(new Instant[]{T0, T0}, new Instant[]{T0.plusSeconds(60), T0})) {
            assertThatThrownBy(() -> store.findDead(bounds[0], bounds[1], PageQuery.of(1, 10)))
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("上界严格晚于下界")
                    .hasMessageContaining(String.valueOf(bounds[0]))
                    .hasMessageContaining(String.valueOf(bounds[1]));
        }
        assertThat(connections.get()).as("窗口本身不合法时不该走到数据库").isZero();

        assertThat(store.findDead(T0, T0.plusMillis(1), PageQuery.of(1, 10)).total()).isZero();
    }

    @Test
    void should_refuse_a_missing_page_parameter() {
        assertThatThrownBy(() -> store.findDead(null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("分页参数");
    }

    // ---------------------------------------------------------------- requeue

    @Test
    void should_move_only_the_dead_rows_it_was_given_and_report_the_real_count() {
        insert("d1", OutboxStatus.DEAD, T0);
        insert("d2", OutboxStatus.DEAD, T0.plusSeconds(1));
        insert("p1", OutboxStatus.PENDING, T0.plusSeconds(2));
        insert("y1", OutboxStatus.DISPATCHING, T0.plusSeconds(3));

        assertThat(store.requeue(List.of("d1", "p1", "y1", "missing"), T0.plusSeconds(300))).isEqualTo(1);

        assertThat(text("d1", "status")).isEqualTo("PENDING");
        assertThat(text("p1", "status")).isEqualTo("PENDING");
        assertThat(number("p1", "retry_count")).as("一条不带谓词的 update 会把正在投递的记录也重置掉").isEqualTo(3);
        assertThat(text("y1", "status")).isEqualTo("DISPATCHING");
        assertThat(instant("y1", "next_retry_at")).as("重放时刻没有渗到别人身上").isEqualTo(T0.plusSeconds(63));
        assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class)).isEqualTo(4);
    }

    @Test
    void should_reset_the_retry_clock_and_clear_the_lease_when_requeuing() {
        insert("d1", OutboxStatus.DEAD, T0);
        Instant now = T0.plusSeconds(600);

        assertThat(store.requeue(List.of("d1"), now)).isEqualTo(1);

        assertThat(text("d1", "status")).isEqualTo("PENDING");
        assertThat(number("d1", "retry_count")).as("重放是从头再试，不继承历史上那 3 次失败").isZero();
        assertThat(instant("d1", "next_retry_at")).isEqualTo(now);
        assertThat(instant("d1", DdlReference.LEASE_COLUMN)).as("旧租约必须清掉：留着它，下一个持有者的租约判断就读的是上一条记录").isNull();
    }

    @Test
    void should_keep_payload_and_failure_reason_across_a_requeue() {
        insert("d1", OutboxStatus.DEAD, T0);

        store.requeue(List.of("d1"), T0.plusSeconds(600));

        assertThat(text("d1", "payload")).isEqualTo("{\"n\":1}");
        assertThat(text("d1", "last_error")).as("重放后仍留原文：下一次尝试若再失败会覆盖它，若成功则由投递器归档")
                .isEqualTo("boom-d1");
    }

    @Test
    void should_hand_the_requeued_record_back_to_the_dispatcher() {
        insert("d1", OutboxStatus.DEAD, T0);
        JdbcOutboxStore writeSide = new JdbcOutboxStore(dataSource,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), table);
        Instant now = T0.plusSeconds(600);

        assertThat(writeSide.claimDispatchable(now, now.plusSeconds(60), 10)).isEmpty();
        assertThat(store.requeue(List.of("d1"), now)).isEqualTo(1);

        assertThat(writeSide.claimDispatchable(now, now.plusSeconds(60), 10))
                .extracting(OutboxRecord::eventId).containsExactly("d1");
        assertThat(text("d1", "status")).isEqualTo("DISPATCHING");
    }

    @Test
    void should_not_touch_the_database_for_an_absent_or_empty_batch() {
        insert("d1", OutboxStatus.DEAD, T0);

        assertThat(store.requeue(null, T0)).isZero();
        assertThat(store.requeue(List.of(), T0)).isZero();

        assertThat(connections.get()).as("空批不必问数据库\"有多少行要改\"").isZero();
        assertThat(text("d1", "status")).isEqualTo("DEAD");
    }

    @Test
    void should_refuse_a_batch_larger_than_the_limit_without_touching_the_database() {
        store = newStore(2);
        insert("d1", OutboxStatus.DEAD, T0);
        insert("d2", OutboxStatus.DEAD, T0.plusSeconds(1));
        insert("d3", OutboxStatus.DEAD, T0.plusSeconds(2));

        assertThatThrownBy(() -> store.requeue(List.of("d1", "d2", "d3"), T0))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("一次重放最多 2 条")
                .hasMessageContaining("实际传入 3 条")
                .hasMessageContaining("myddd.persistence.outbox.dead-letter.query-limit");

        assertThat(connections.get()).isZero();
        assertThat(idsOf("select event_id from " + table + " where status = 'DEAD'"))
                .containsExactlyInAnyOrder("d1", "d2", "d3");
    }

    @Test
    void should_requeue_a_batch_whose_size_equals_the_limit() {
        // 上界那一侧的边界：把"超过"写成"达到"会让运维界面每满 200 条就点不动一次重放。
        store = newStore(2);
        insert("d1", OutboxStatus.DEAD, T0);
        insert("d2", OutboxStatus.DEAD, T0.plusSeconds(1));

        assertThat(store.requeue(List.of("d1", "d2"), T0.plusSeconds(600))).isEqualTo(2);
        assertThat(text("d1", "status")).isEqualTo("PENDING");
        assertThat(text("d2", "status")).isEqualTo("PENDING");
    }

    @Test
    void should_accept_a_query_limit_of_exactly_one() {
        // 下界那一侧：1 是"一页一条"的合法配法，只有 0 与负数才是配错了。
        JdbcOutboxDeadLetterStore single = newStore(1);
        insert("d1", OutboxStatus.DEAD, T0);
        insert("d2", OutboxStatus.DEAD, T0.plusSeconds(1));

        assertThat(single.queryLimit()).isEqualTo(1);
        assertThat(ids(single.findDead(null, null, PageQuery.of(1, 50)))).containsExactly("d2");
        assertThat(single.findDead(null, null, PageQuery.of(1, 50)).total()).isEqualTo(2L);
    }

    @Test
    void should_refuse_null_or_blank_event_ids_before_touching_the_database() {
        List<List<String>> batches = List.of(Arrays.asList("d1", null), Arrays.asList("d1", "  "),
                Arrays.asList("d1", "\t"));

        for (List<String> batch : batches) {
            assertThatThrownBy(() -> store.requeue(batch, T0))
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("null 或空白");
        }
        assertThat(connections.get()).isZero();
    }

    @Test
    void should_require_a_requeue_moment_because_it_becomes_the_next_retry_time() {
        assertThatThrownBy(() -> store.requeue(List.of("d1"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("重放时刻");
    }

    // ------------------------------------------------------------- 构造期守卫

    @Test
    void should_reject_a_query_limit_below_one_at_construction() {
        for (int limit : new int[]{0, -1}) {
            assertThatThrownBy(() -> new JdbcOutboxDeadLetterStore(dataSource, readTransaction, table, limit))
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("myddd.persistence.outbox.dead-letter.query-limit 必须 ≥ 1")
                    .hasMessageContaining(String.valueOf(limit));
        }
    }

    @Test
    void should_share_the_table_name_guard_with_the_write_side() {
        assertThatThrownBy(() -> new JdbcOutboxDeadLetterStore(dataSource, readTransaction,
                "myddd_outbox; drop table myddd_outbox", DEFAULT_LIMIT))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("[a-z0-9_]{1,64}")
                .hasMessageContaining("myddd.persistence.outbox.table");

        assertThatThrownBy(() -> new JdbcOutboxDeadLetterStore(dataSource, readTransaction, null, DEFAULT_LIMIT))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Outbox 表名不能为 null");

        assertThatThrownBy(() -> new JdbcOutboxDeadLetterStore(dataSource, null, table, DEFAULT_LIMIT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("事务执行器");
    }

    @Test
    void should_hint_the_reference_ddl_when_the_first_statement_hits_a_missing_table() {
        jdbc.execute("drop table " + table);

        assertThatThrownBy(() -> store.findDead(null, null, PageQuery.of(1, 10)))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining(DdlReference.OUTBOX_DDL)
                .hasMessageContaining("findDead")
                .hasCauseInstanceOf(BadSqlGrammarException.class);
    }

    @Test
    void should_report_a_plain_grammar_error_once_a_statement_has_succeeded() {
        insert("d1", OutboxStatus.DEAD, T0);
        store.findDead(null, null, PageQuery.of(1, 10));
        jdbc.execute("drop table " + table);

        assertThatThrownBy(() -> store.findDead(null, null, PageQuery.of(1, 10)))
                .isInstanceOf(BadSqlGrammarException.class)
                .hasMessageNotContaining("参考建表脚本");
    }

    @Test
    void should_read_and_requeue_only_the_configured_table() {
        String custom = "my_order_dead_letter";
        table = custom;
        store = newStore(DEFAULT_LIMIT);
        createReferenceSchema(custom);
        insert("d1", OutboxStatus.DEAD, T0);
        insert("other", OutboxStatus.DEAD, T0.plusSeconds(1), DdlReference.OUTBOX_TABLE);

        assertThat(ids(store.findDead(null, null, PageQuery.of(1, 10)))).containsExactly("d1");
        assertThat(store.requeue(List.of("other"), T0)).as("默认表里那条同 id 的死信不是本表的东西").isZero();
        assertThat(store.requeue(List.of("d1"), T0)).isEqualTo(1);
    }

    @Test
    void should_expose_the_effective_limit_to_the_caller() {
        assertThat(store.queryLimit()).isEqualTo(DEFAULT_LIMIT);
        assertThat(newStore(7).queryLimit()).isEqualTo(7);
    }

    // ----------------------------------------------------------------- 夹具

    private JdbcOutboxDeadLetterStore newStore(int limit) {
        return new JdbcOutboxDeadLetterStore(dataSource, readTransaction, table, limit);
    }

    private void createReferenceSchema(String target) {
        String ddl = DdlReference.read(DdlReference.OUTBOX_DDL);
        if (!target.equals(DdlReference.OUTBOX_TABLE)) {
            ddl = ddl.replace(DdlReference.OUTBOX_TABLE, target);
        }
        Arrays.stream(ddl.split(";")).map(String::trim).filter(statement -> !statement.isEmpty())
                .forEach(jdbc::execute);
    }

    /**
     * 字面 insert：列名逐个写死，不借被测代码拼 SQL。
     *
     * <p>{@code dispatch_lease_until} 一律给值（含 PENDING 与 DISPATCHED）：读侧不看它，而重放那一组需要
     * "重放前有值、重放后为 null" 这个可观测差量。
     */
    private void insert(String eventId, OutboxStatus status, Instant createdAt) {
        insert(eventId, status, createdAt, table);
    }

    private void insert(String eventId, OutboxStatus status, Instant createdAt, String target) {
        jdbc.update("insert into " + target + " (event_id, event_type, aggregate_type, aggregate_id, trace_id,"
                        + " occurred_at, channel, payload, status, retry_count, next_retry_at, last_error,"
                        + " created_at, dispatch_lease_until)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                eventId, "org.deep361.sample.OrderCancelled", "org.deep361.sample.Order", "order-" + eventId,
                "trace-" + eventId, Timestamp.from(createdAt.minusSeconds(1)), "order-events", "{\"n\":1}",
                status.name(), 3, Timestamp.from(createdAt.plusSeconds(60)), "boom-" + eventId,
                Timestamp.from(createdAt), Timestamp.from(createdAt.plusSeconds(300)));
    }

    private static List<String> ids(PageResult<OutboxRecord> page) {
        return page.items().stream().map(OutboxRecord::eventId).toList();
    }

    private List<String> idsOf(String select) {
        return jdbc.query(select, (rs, n) -> rs.getString(1));
    }

    private String text(String eventId, String column) {
        return jdbc.queryForObject("select " + column + " from " + table + " where event_id = ?", String.class,
                eventId);
    }

    private Integer number(String eventId, String column) {
        return jdbc.queryForObject("select " + column + " from " + table + " where event_id = ?", Integer.class,
                eventId);
    }

    private Instant instant(String eventId, String column) {
        Timestamp value = jdbc.queryForObject("select " + column + " from " + table + " where event_id = ?",
                Timestamp.class, eventId);
        return value == null ? null : value.toInstant();
    }

    private static DataSource counting(DataSource delegate, AtomicInteger connections) {
        return (DataSource) Proxy.newProxyInstance(JdbcOutboxDeadLetterStoreTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if (method.getName().startsWith("getConnection")) {
                        connections.incrementAndGet();
                    }
                    return forward(delegate, method, args);
                });
    }

    /**
     * 把连接元数据里的产品名换成 {@code product}，其余一路原样转发——包括连接本身。
     *
     * <p>为什么能这么换：{@code PagingClause} 判方言只读 {@code getDatabaseProductName()} 这一个字符串，
     * 而执行子句用的是真 H2（它两种形态都认）。于是 MySQL 那一支也能被真查询证伪，不必退化成比字符串。
     */
    private static DataSource reportingAs(DataSource delegate, String product) {
        return reportingAs(delegate, product, new ArrayList<>());
    }

    /** 第三参数收下发给连接的 SQL 文本：方言判定的结论只有落在文本上才看得见，见 {@code clauseSentTo}。 */
    private static DataSource reportingAs(DataSource delegate, String product, List<String> statementSink) {
        return (DataSource) Proxy.newProxyInstance(JdbcOutboxDeadLetterStoreTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    Object result = forward(delegate, method, args);
                    return result instanceof Connection connection
                            ? connectionAs(connection, product, statementSink) : result;
                });
    }

    private static Connection connectionAs(Connection delegate, String product, List<String> statementSink) {
        return (Connection) Proxy.newProxyInstance(JdbcOutboxDeadLetterStoreTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName()) && args != null && args.length > 0
                            && args[0] instanceof String sql) {
                        statementSink.add(sql);
                    }
                    Object result = forward(delegate, method, args);
                    return result instanceof DatabaseMetaData metaData ? metaDataAs(metaData, product) : result;
                });
    }

    private static DatabaseMetaData metaDataAs(DatabaseMetaData delegate, String product) {
        return (DatabaseMetaData) Proxy.newProxyInstance(JdbcOutboxDeadLetterStoreTest.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class}, (proxy, method, args) -> "getDatabaseProductName"
                        .equals(method.getName()) ? product : forward(delegate, method, args));
    }

    /** 转发一次调用，并把反射包装还原成原始异常：代理不该改变异常的形状。 */
    private static Object forward(Object delegate, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
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
