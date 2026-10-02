package org.deep361.myddd.persistence.it;

import org.deep361.myddd.domain.outbox.OutboxRecord;
import org.deep361.myddd.domain.outbox.OutboxStatus;
import org.deep361.myddd.persistence.ddl.DdlReference;
import org.deep361.myddd.persistence.outbox.JdbcOutboxStore;
import org.deep361.myddd.testing.container.MydddMysqlContainer;
import org.deep361.myddd.testing.integration.MydddIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code JdbcOutboxStore} 在真实 MySQL 8 上的契约验收（Plan 3 Task 20）。
 *
 * <p><b>本类只做单元层做不了的那半件事</b>：把 SQL 交给 MySQL 自己的解析器与存储引擎。
 * Task 19 的 24 条用例跑在 H2 {@code MODE=MySQL} 上，那里"看起来一样"的东西在这里才见分晓：
 * 认领用的那条带谓词的 {@code update}（比较并交换）在 InnoDB 的默认隔离级别下是否真的做到"同一行只会被
 * 一个持有者拿走"、主键冲突的具体异常子类型、{@code datetime(3)} 的精度、
 * {@code mediumtext} 是否原样存原文，以及参考 DDL 是否真的能在 MySQL 上建出来。
 *
 * <p><b>并发认领钉的是"不重不漏"，不是"均分"</b>：候选集查询<b>不</b>加行锁，两个认领者可能选到同一批行，
 * 由逐行的比较并交换裁决归属——所以"两边各拿到 {@code limit} 条"这种保证本来就不属于这套实现，
 * 这里钉的是在任何库上都成立的那两条：两批绝不交集，被标成 {@code DISPATCHING} 的行恰好等于两批的全集。
 *
 * <p><b>表由被测交付物自己建</b>：建表脚本读 {@code db/ddl-reference/V1__myddd_outbox.sql}
 * ——与业务工程复制走的是同一份文件，所以本类同时是"这份 DDL 可直接执行"的证据。
 *
 * <p><b>无 Docker 时整类跳过</b>：基注解 {@link MydddIntegrationTest} 带
 * {@code @Testcontainers(disabledWithoutDocker = true)}，这是 §0.3-7 的实现路径，
 * Task 5 的 {@code MydddIntegrationTestContractTest} 已在仓库内取证过"跳过而不是失败"。
 */
@MydddIntegrationTest
@ImportTestcontainers(MydddMysqlContainer.class)
@TestPropertySource(properties = "myddd.persistence.outbox.enabled=true")
class OutboxMySqlIT {

    private static final Instant T0 = Instant.parse("2026-09-26T10:20:30.123Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcOutboxStore store;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void recreateReferenceTable() throws SQLException {
        // 参考 DDL 是迁移脚本形态（create table，刻意不带 if not exists），而 MySQL 容器在用例类之间是复用的：
        // 不清场的话第二个用例类就撞上 "Table already exists"。先按交付的表名清场，再把那份脚本原样执行一遍
        // ——"每个用例都真的从干净库建成功"本身就是本类的一条证据。
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("drop table if exists " + DdlReference.OUTBOX_TABLE);
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ClassPathResource(DdlReference.OUTBOX_DDL), StandardCharsets.UTF_8));
        }
    }

    private static OutboxRecord pending(String eventId, Instant occurredAt, String payload) {
        return OutboxRecord.pending(eventId, "it.probe.Paid", "it.probe.Order", eventId + "-agg", occurredAt,
                "trace-" + eventId, "probe-channel", payload, T0);
    }

    private static Timestamp at(Instant instant) {
        return Timestamp.from(instant);
    }

    @Test
    void should_create_the_reference_table_with_both_claim_indexes_on_real_mysql() {
        List<String> indexes = jdbc.queryForList("select distinct index_name from information_schema.statistics"
                + " where table_schema = schema() and table_name = ?", String.class, DdlReference.OUTBOX_TABLE);
        assertThat(indexes).contains("PRIMARY", "idx_myddd_outbox_dispatchable", "idx_myddd_outbox_lease");

        List<String> leaseColumns = jdbc.queryForList("select column_name from information_schema.statistics"
                + " where table_schema = schema() and table_name = ? and index_name = ?"
                + " order by seq_in_index", String.class, DdlReference.OUTBOX_TABLE, "idx_myddd_outbox_lease");
        assertThat(leaseColumns).as("租约索引的列序是认领 SQL 能用上它的前提")
                .containsExactly("status", DdlReference.LEASE_COLUMN);

        Integer created = jdbc.queryForObject("select count(*) from information_schema.tables"
                + " where table_schema = schema() and table_name = ?", Integer.class, DdlReference.OUTBOX_TABLE);
        assertThat(created).as("参考 DDL 必须真的建出表来").isEqualTo(1);
    }

    @Test
    void should_report_duplicate_key_as_sql_integrity_violation_and_keep_the_original_row() {
        store.save(pending("dup-1", T0, "{\"amount\":1}"));

        assertThatThrownBy(() -> store.save(pending("dup-1", T0.plusSeconds(1), "{\"amount\":2}")))
                .isInstanceOf(DataIntegrityViolationException.class)
                // H2 给的是它自己的 JdbcSQLIntegrityConstraintViolationException；真库要钉住 JDBC 标准子类型，
                // 业务的捕获面才能在任何 MySQL 8 上都成立（Task 19 要点 6 的取证）。
                .rootCause().isInstanceOf(SQLIntegrityConstraintViolationException.class);

        assertThat(jdbc.queryForObject("select payload from myddd_outbox where event_id = 'dup-1'", String.class))
                .isEqualTo("{\"amount\":1}");
        assertThat(jdbc.queryForObject("select count(*) from myddd_outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void should_keep_milliseconds_in_datetime3_and_declare_precision_three() {
        store.save(pending("millis-1", T0, "{}"));

        Instant readBack = jdbc.query("select occurred_at from myddd_outbox where event_id = 'millis-1'",
                resultSet -> {
                    resultSet.next();
                    return resultSet.getTimestamp(1).toInstant();
                });
        assertThat(readBack).as("写入带 123 毫秒，读回必须逐毫秒相等（不许截断到秒）").isEqualTo(T0);
        assertThat(readBack.getNano()).as("毫秒位仍在，不是被抹平成整秒").isNotZero();

        Integer precision = jdbc.queryForObject("select datetime_precision from information_schema.columns"
                + " where table_schema = schema() and table_name = 'myddd_outbox' and column_name = 'occurred_at'",
                Integer.class);
        assertThat(precision).isEqualTo(3);
    }

    @Test
    void should_round_trip_a_one_megabyte_payload_verbatim_without_server_rewrite() {
        StringBuilder payload = new StringBuilder(1024 * 1024 + 64);
        payload.append("{\"text\":\"");
        while (payload.length() < 1024 * 1024) {
            payload.append("中文与 ASCII 混排 abcdefghijklmnopqrstuvwxyz0123456789 ");
        }
        payload.append("\"}");
        String written = payload.toString();

        store.save(pending("blob-1", T0, written));

        assertThat(jdbc.queryForObject("select payload from myddd_outbox where event_id = 'blob-1'", String.class))
                .as("mediumtext 必须存原文：不做 JSON 规范化、不截断")
                .isEqualTo(written);
    }

    @Test
    void should_let_two_concurrent_dispatchers_claim_without_overlap_or_lost_rows() throws Exception {
        for (int i = 0; i < 6; i++) {
            store.save(pending("claim-" + i, T0, "{}"));
        }
        // 起跑闸门让两个认领者在同一时刻进入候选集查询：重叠的候选集正是本条用例要在真引擎上看清的东西。
        CountDownLatch startGate = new CountDownLatch(1);
        Instant leaseUntil = T0.plus(Duration.ofMinutes(5));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<List<OutboxRecord>>> futures = List.of(
                    pool.submit(() -> claimOnce(startGate, leaseUntil, 3)),
                    pool.submit(() -> claimOnce(startGate, leaseUntil, 3)));
            startGate.countDown();
            List<OutboxRecord> first = futures.get(0).get(20, TimeUnit.SECONDS);
            List<OutboxRecord> second = futures.get(1).get(20, TimeUnit.SECONDS);

            List<String> both = new ArrayList<>(first.size() + second.size());
            both.addAll(idsOf(first));
            both.addAll(idsOf(second));
            // 两批不交集用"合起来没有重复"表达：每一批内部本来就不可能有重复（认领按主键点名），
            // 而 doesNotContainAnyElementsOf 在对方为空集时会抛 IllegalArgumentException——
            // "第二个认领者一条都没抢到"恰恰是比较并交换下最该被接受的结果，不能把它写成夹具异常。
            assertThat(both).doesNotHaveDuplicates();
            // 两批各挑 3 条：谁先提交谁把对方候选集里的行带走，所以全集在 3..6 之间浮动是正常结果，
            // 但每一行都必须有归属——"两边都以为拿到了"或"谁都没拿到"都是丢消息。
            assertThat(both).hasSizeBetween(3, 6).isSubsetOf(List.of(
                    "claim-0", "claim-1", "claim-2", "claim-3", "claim-4", "claim-5"));
            assertThat(jdbc.queryForList("select event_id from myddd_outbox where status = ?",
                    String.class, OutboxStatus.DISPATCHING.name()))
                    .as("库里被标成 DISPATCHING 的行必须恰好等于两批认领结果的全集")
                    .containsExactlyInAnyOrderElementsOf(both);
            assertThat(jdbc.queryForList("select event_id from myddd_outbox where status = ?",
                    String.class, OutboxStatus.PENDING.name()))
                    .as("没被认领的行原样留在 PENDING：并发不许丢消息")
                    .hasSize(6 - both.size());
        } finally {
            pool.shutdownNow();
        }
    }

    private List<OutboxRecord> claimOnce(CountDownLatch startGate, Instant leaseUntil, int limit)
            throws InterruptedException {
        startGate.await(10, TimeUnit.SECONDS);
        // 认领批次一返回就提交：锁只在批次的时长内持有，不在断言期间持有，这是比较并交换设计的代价与边界。
        return new TransactionTemplate(transactionManager).execute(
                status -> store.claimDispatchable(T0, leaseUntil, limit));
    }

    @Test
    void should_reclaim_the_same_row_after_the_lease_expires_and_keep_it_dispatching() {
        store.save(pending("lease-1", T0, "{}"));
        assertThat(store.claimDispatchable(T0, T0.plus(Duration.ofMinutes(5)), 10))
                .extracting(OutboxRecord::eventId).containsExactly("lease-1");

        // 租约没到期就认领不到：崩溃中的实例不会被人抢走正在投递的消息
        assertThat(store.claimDispatchable(T0.plusSeconds(60), T0.plusSeconds(300), 10)).isEmpty();

        jdbc.update("update myddd_outbox set " + DdlReference.LEASE_COLUMN + " = ? where event_id = 'lease-1'",
                at(T0.plusSeconds(1)));
        List<OutboxRecord> reclaimed = store.claimDispatchable(T0.plusSeconds(120), T0.plusSeconds(420), 10);

        assertThat(reclaimed).extracting(OutboxRecord::eventId).containsExactly("lease-1");
        assertThat(reclaimed.get(0).status()).isEqualTo(OutboxStatus.DISPATCHING);
        assertThat(jdbc.queryForObject("select count(*) from myddd_outbox where event_id = 'lease-1'", Integer.class))
                .as("回收是同一条行的状态改写，不许插出第二行").isEqualTo(1);
    }

    @Test
    void should_store_the_proxy_class_name_verbatim_across_connections() throws SQLException {
        // Task 18 交下来的义务：污染是"跨进程可见"的，而不只是单测里的字符串比较。
        // 这里绕过发布器直接写一行代理类名，再用另一条连接原样读回。
        // 聚合 id 给的是实值而不是 null：OutboxRecord 的规范构造器 requireText(aggregateId)，参考 DDL 里
        // aggregate_id 也是 not null——真库在插入时就拒绝这行，而读回来的路也过不去（toRecord 会抛）。
        jdbc.update("insert into myddd_outbox (event_id, event_type, aggregate_type, aggregate_id, occurred_at,"
                        + " channel, payload, status, retry_count, next_retry_at, created_at)"
                        + " values ('cglib-1','it.probe.Paid',?,?,?,'probe-channel','{}','PENDING',0,?,?)",
                "it.probe.Order$$SpringCGLIB$$0", "agg-cglib-1", at(T0), at(T0), at(T0));

        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select aggregate_type, aggregate_id from myddd_outbox where event_id = 'cglib-1'")) {
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).as("库内存的就是代理类名原文，MySQL 侧不做任何清洗")
                    .isEqualTo("it.probe.Order$$SpringCGLIB$$0");
            assertThat(rs.getString(2)).isEqualTo("agg-cglib-1");
        }
        assertThat(store.claimDispatchable(T0, T0.plus(Duration.ofMinutes(5)), 10))
                .extracting(OutboxRecord::eventId).contains("cglib-1");
    }

    private static List<String> idsOf(List<OutboxRecord> records) {
        return records.stream().map(OutboxRecord::eventId).toList();
    }
}
