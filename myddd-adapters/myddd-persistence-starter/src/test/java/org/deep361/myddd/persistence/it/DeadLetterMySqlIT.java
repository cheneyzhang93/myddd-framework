package org.deep361.myddd.persistence.it;

import org.deep361.myddd.domain.outbox.OutboxRecord;
import org.deep361.myddd.domain.outbox.OutboxStatus;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.common.page.PageQuery;
import org.deep361.myddd.kernel.common.page.PageResult;
import org.deep361.myddd.persistence.ddl.DdlReference;
import org.deep361.myddd.persistence.outbox.JdbcOutboxDeadLetterStore;
import org.deep361.myddd.persistence.outbox.JdbcOutboxStore;
import org.deep361.myddd.persistence.outbox.OutboxDeadLetterStore;
import org.deep361.myddd.testing.container.MydddMysqlContainer;
import org.deep361.myddd.testing.integration.MydddIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code JdbcOutboxDeadLetterStore} 在真实 MySQL 8 上的读侧验收（Plan 3 Task 21 的 Docker 步骤）。
 *
 * <p><b>与 D 组用例的分工</b>：{@code JdbcOutboxDeadLetterStoreTest} 跑在 H2 的 {@code MODE=MySQL} 上，
 * 钉的是<b>我们的代码形状</b>；这里钉的是<b>MySQL 自己怎么解释同一批 SQL</b>——三件 H2 给不了证据的事：
 * {@code PagingClause} 在真库上解析出的 {@code limit ? offset ?} 用占位符（而不是字面量）是否被接受、
 * 翻到最后一页的行为、以及 {@code in (?, ?, …)} 长到 200 个占位符时（生产上限）一次批量重放是否还是一条语句走得完。
 *
 * <p><b>表与 bean 都来自交付物本身</b>：建表用 {@code db/ddl-reference/V1__myddd_outbox.sql} 原文，
 * 读侧 bean 由 {@code MydddOutboxDeadLetterAutoConfiguration} 装配（{@code myddd.persistence.outbox.enabled=true}）
 * ——所以本类同时是"第 4 个装配类在真上下文里起得来"的证据，这是单元层的 {@code ApplicationContextRunner}
 * 证明不了的那一半。
 *
 * <p><b>无 Docker 时整类跳过</b>：基注解 {@link MydddIntegrationTest} 带
 * {@code @Testcontainers(disabledWithoutDocker = true)}（§0.3-7），与 Task 20 的三个 IT 同一条路径。
 */
@MydddIntegrationTest
@ImportTestcontainers(MydddMysqlContainer.class)
@TestPropertySource(properties = "myddd.persistence.outbox.enabled=true")
class DeadLetterMySqlIT {

    private static final Instant T0 = Instant.parse("2026-09-26T10:20:30.123Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OutboxDeadLetterStore deadLetters;

    @Autowired
    private JdbcOutboxStore outbox;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void recreateReferenceTable() throws Exception {
        // 参考 DDL 是迁移脚本形态（create table，刻意不带 if not exists），而同一个 MySQL 容器在用例类之间
        // 是复用的：不清场的话第二个用例类就撞上 "Table already exists"。先按交付的表名清场，再把那份脚本
        // 原样执行一遍——"能执行成功"本身就是本类要的证据，且每个用例都重演一次。
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("drop table if exists " + DdlReference.OUTBOX_TABLE);
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ClassPathResource(DdlReference.OUTBOX_DDL), StandardCharsets.UTF_8));
        }
    }

    /** 直接按参考 DDL 的列写一行指定状态的记录：读侧的夹具不该依赖写侧的"只接受 PENDING"。 */
    private void insert(String eventId, OutboxStatus status, Instant createdAt) {
        jdbc.update("insert into " + DdlReference.OUTBOX_TABLE + " (event_id, event_type, aggregate_type,"
                        + " aggregate_id, trace_id, occurred_at, channel, payload, status, retry_count,"
                        + " next_retry_at, last_error, created_at, dispatch_lease_until)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                eventId, "it.probe.OrderCancelled", "it.probe.Order", "agg-" + eventId, "trace-" + eventId,
                Timestamp.from(createdAt.minusSeconds(1)), "probe-channel", "{\"n\":1}", status.name(), 3,
                Timestamp.from(createdAt.plusSeconds(60)), "boom-" + eventId, Timestamp.from(createdAt),
                Timestamp.from(createdAt.plusSeconds(300)));
    }

    private static List<String> idsOf(PageResult<OutboxRecord> page) {
        return page.items().stream().map(OutboxRecord::eventId).toList();
    }

    @Test
    void should_page_through_dead_letters_newest_first_without_overlap_or_gaps() {
        for (int i = 1; i <= 5; i++) {
            insert("d" + i, OutboxStatus.DEAD, T0.plusSeconds(i));
        }
        insert("p1", OutboxStatus.PENDING, T0.plusSeconds(3));
        insert("x1", OutboxStatus.DISPATCHED, T0.plusSeconds(4));

        List<String> paged = new ArrayList<>();
        for (long pageNum = 1; pageNum <= 3; pageNum++) {
            PageResult<OutboxRecord> page = deadLetters.findDead(null, null, PageQuery.of(pageNum, 2));
            assertThat(page.total()).as("每页都报同一个总数").isEqualTo(5L);
            paged.addAll(idsOf(page));
        }

        assertThat(paged).containsExactly("d5", "d4", "d3", "d2", "d1");
    }

    @Test
    void should_filter_the_window_on_the_server_and_keep_millisecond_precision() {
        insert("inside", OutboxStatus.DEAD, T0);
        insert("outside", OutboxStatus.DEAD, T0.plusSeconds(120));
        insert("before-window", OutboxStatus.DEAD, T0.minusSeconds(120));

        PageResult<OutboxRecord> page = deadLetters.findDead(T0, T0.plusSeconds(60), PageQuery.of(1, 10));

        assertThat(idsOf(page)).containsExactly("inside");
        assertThat(page.items().get(0).createdAt()).as("datetime(3) 往返到毫秒").isEqualTo(T0);
        assertThat(page.items().get(0).lastError()).isEqualTo("boom-inside");
    }

    @Test
    void should_report_an_empty_page_but_the_real_total_past_the_last_page() {
        insert("d1", OutboxStatus.DEAD, T0);
        insert("d2", OutboxStatus.DEAD, T0.plusSeconds(1));

        PageResult<OutboxRecord> beyond = deadLetters.findDead(null, null, PageQuery.of(9, 2));

        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.total()).isEqualTo(2L);
    }

    @Test
    void should_requeue_only_dead_rows_and_clear_their_lease_in_one_statement() {
        insert("d1", OutboxStatus.DEAD, T0);
        insert("p1", OutboxStatus.PENDING, T0.plusSeconds(1));
        insert("y1", OutboxStatus.DISPATCHING, T0.plusSeconds(2));
        Instant now = T0.plusSeconds(600);

        assertThat(deadLetters.requeue(List.of("d1", "p1", "y1", "absent"), now)).isEqualTo(1);

        List<String> states = jdbc.queryForList("select concat(status, ':', retry_count, ':',"
                + " coalesce(dispatch_lease_until is null, 0)) from " + DdlReference.OUTBOX_TABLE
                + " where event_id = 'd1'", String.class);
        assertThat(states).containsExactly("PENDING:0:1");

        assertThat(jdbc.queryForObject("select retry_count from " + DdlReference.OUTBOX_TABLE
                + " where event_id = 'p1'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select status from " + DdlReference.OUTBOX_TABLE
                + " where event_id = 'y1'", String.class)).isEqualTo("DISPATCHING");
    }

    @Test
    void should_hand_a_requeued_record_back_to_the_dispatcher_on_a_real_connection() {
        insert("d1", OutboxStatus.DEAD, T0);
        Instant now = T0.plusSeconds(600);

        assertThat(outbox.claimDispatchable(now, now.plus(Duration.ofMinutes(5)), 10)).isEmpty();
        assertThat(deadLetters.requeue(List.of("d1"), now)).isEqualTo(1);

        assertThat(outbox.claimDispatchable(now, now.plus(Duration.ofMinutes(5)), 10))
                .extracting(OutboxRecord::eventId).containsExactly("d1");
    }

    @Test
    void should_bind_a_full_production_batch_of_two_hundred_ids_in_one_statement() {
        List<String> eventIds = new ArrayList<>(deadLetters.queryLimit());
        for (int i = 0; i < deadLetters.queryLimit(); i++) {
            String eventId = "bulk-" + i;
            eventIds.add(eventId);
            insert(eventId, OutboxStatus.DEAD, T0.plusMillis(i));
        }

        assertThat(deadLetters.requeue(eventIds, T0.plusSeconds(600))).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from " + DdlReference.OUTBOX_TABLE + " where status = ?"
                + " and retry_count = 0", Integer.class, OutboxStatus.PENDING.name())).isEqualTo(200);
    }

    @Test
    void should_refuse_an_oversized_batch_before_sending_anything() {
        JdbcOutboxDeadLetterStore small = new JdbcOutboxDeadLetterStore(dataSource,
                new TransactionTemplate(transactionManager), DdlReference.OUTBOX_TABLE, 2);
        insert("d1", OutboxStatus.DEAD, T0);
        insert("d2", OutboxStatus.DEAD, T0.plusSeconds(1));
        insert("d3", OutboxStatus.DEAD, T0.plusSeconds(2));

        assertThatThrownBy(() -> small.requeue(List.of("d1", "d2", "d3"), T0))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("一次重放最多 2 条");
        assertThat(jdbc.queryForObject("select count(*) from " + DdlReference.OUTBOX_TABLE + " where status = ?",
                Integer.class, OutboxStatus.DEAD.name())).isEqualTo(3);

        assertThat(small.requeue(List.of("d1", "d2"), T0)).as("等于上限必须放行")
                .isEqualTo(2);
    }

    @Test
    void should_assemble_the_dead_letter_store_as_the_fourth_auto_configuration() {
        // 上下文里那个 bean 是第 4 个装配类产出的：单元层的 ApplicationContextRunner 只起得到条件注解，
        // 起不到"与写侧、路由、审计装配共处一个真上下文"这件事。
        assertThat(deadLetters).isInstanceOf(JdbcOutboxDeadLetterStore.class);
        assertThat(deadLetters.queryLimit()).as("缺省上限 200 从配置绑定进了真上下文").isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema = schema()"
                + " and table_name = ?", Integer.class, DdlReference.OUTBOX_TABLE)).isEqualTo(1);
    }
}
