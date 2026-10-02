package org.deep361.myddd.persistence.it;

import org.deep361.myddd.persistence.autoconfigure.MydddPersistenceProperties;
import org.deep361.myddd.testing.container.MydddMysqlContainer;
import org.deep361.myddd.testing.integration.MydddIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计填充在真实 MyBatis-Plus + 真实 MySQL 上的接线验收（Plan 3 Task 20，兑现 Task 14 设计要点 6）。
 *
 * <p>Task 14 的单元层用 {@code SystemMetaObject.forObject(po)} 直接驱动 {@code MetaObjectHandler}，
 * 测的是<b>决策</b>（填哪四列、何时不填）；"MP 在 insert/update 前到底回调了我们没有"属<b>接线</b>，
 * 只有真的走一遍 SqlSessionFactory 与数据库才谈得上证据。本类补的就是这一半。
 *
 * <p><b>两种时机的覆盖语义不同，本类各钉一条</b>：插入用 {@code strictInsertFill}（只在字段当前为
 * {@code null} 时写，所以 {@code should_keep_explicit_audit_values_on_insert_because_strict_fill_only_fills_nulls}
 * 里显式赋过的导入值原样落库）；更新用 {@code forceUpdateFill}（<b>无条件覆盖</b>，理由在处理器 javadoc：
 * 带判空的 {@code strictUpdateFill} 会让"从库里读出来的实体"永远不改时间戳）。所以
 * {@code should_overwrite_the_update_columns_on_real_mp_update_and_leave_insert_columns_alone} 期望实体里
 * 显式赋的 {@code updater} 被写掉——把它反过来写，等于断言"MP 没回调我们的处理器"。
 *
 * <p>夹具表由本类自己建（{@code it_audit_probe}）：审计列名与 {@code AbstractPO} 的注解同源，
 * 而这张表不属于任何交付物，所以不许进 {@code db/ddl-reference}。
 */
@MydddIntegrationTest
@ImportTestcontainers(MydddMysqlContainer.class)
class AuditMySqlIT {

    private static final String PROBE_TABLE = "it_audit_probe";

    @Autowired
    private AuditProbeMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MydddPersistenceProperties properties;

    @BeforeEach
    void recreateProbeTable() {
        jdbc.execute("drop table if exists " + PROBE_TABLE);
        jdbc.execute("create table " + PROBE_TABLE + " ("
                + " id bigint not null primary key,"
                + " order_no varchar(64) not null,"
                + " create_time datetime(3) not null,"
                + " update_time datetime(3) not null,"
                + " creator varchar(64) not null,"
                + " updater varchar(64) not null) engine = innodb");
    }

    /**
     * 审计四列的原样读回。
     *
     * <p>这里刻意不用 {@code queryForMap}：MySQL Connector/J 把 {@code datetime} 映射成
     * {@code LocalDateTime}，H2 给的是 {@code Timestamp}——按 {@code Object} 取出来再强转就是本类第一版
     * 在真库上栽的那个 {@code ClassCastException}。走 {@code rs.getTimestamp} 让驱动做它自己的双向换算，
     * 写路径与读路径同源，时刻语义与服务器/JVM 时区无关。
     */
    private AuditRow auditRow(String orderNo) {
        return jdbc.queryForObject("select creator, updater, create_time, update_time from " + PROBE_TABLE
                        + " where order_no = ?",
                (resultSet, rowNumber) -> new AuditRow(resultSet.getString("creator"),
                        resultSet.getString("updater"), resultSet.getTimestamp("create_time"),
                        resultSet.getTimestamp("update_time")), orderNo);
    }

    private record AuditRow(String creator, String updater, Timestamp createTime, Timestamp updateTime) {
    }

    private String unknownPrincipal() {
        return properties.getAudit().getUnknownPrincipal();
    }

    @Test
    void should_fill_the_four_audit_columns_on_insert_and_persist_them() {
        AuditProbePO po = new AuditProbePO();
        po.setOrderNo("IT-AUDIT-1");
        assertThat(mapper.insert(po)).isEqualTo(1);

        AuditRow row = auditRow("IT-AUDIT-1");
        String unknownPrincipal = unknownPrincipal();
        assertThat(row.creator()).as("没有登录主体时按配置的 unknown-principal 落库").isEqualTo(unknownPrincipal);
        assertThat(row.updater()).isEqualTo(unknownPrincipal);
        assertThat(row.createTime()).isNotNull();
        assertThat(row.updateTime()).isNotNull();
        assertThat(Math.abs(row.updateTime().getTime() - row.createTime().getTime()))
                .as("插入时创建列与更新列同值（同一刻取时间，容 2 秒时钟误差）").isLessThan(2000L);
    }

    @Test
    void should_keep_explicit_audit_values_on_insert_because_strict_fill_only_fills_nulls() {
        // 历史数据导入与迁移要的就是这条：显式赋过的 creator/createTime 原样落库，处理器不许写掉。
        // 单元层直接驱动过 MetaObjectHandler 证明了这个决策，这里钉的是"真 MP 走的确实是 strictInsertFill"。
        Instant backThen = Instant.parse("2020-01-02T03:04:05.678Z");
        AuditProbePO po = new AuditProbePO();
        po.setOrderNo("IT-AUDIT-2");
        po.setCreator("imported-by");
        po.setUpdater("imported-by");
        po.setCreateTime(backThen);
        po.setUpdateTime(backThen);
        assertThat(mapper.insert(po)).isEqualTo(1);

        AuditRow row = auditRow("IT-AUDIT-2");
        assertThat(row.creator()).isEqualTo("imported-by");
        assertThat(row.updater()).isEqualTo("imported-by");
        assertThat(row.createTime().toInstant()).isEqualTo(backThen);
        assertThat(row.updateTime().toInstant()).isEqualTo(backThen);
    }

    @Test
    void should_overwrite_the_update_columns_on_real_mp_update_and_leave_insert_columns_alone() {
        AuditProbePO po = new AuditProbePO();
        po.setOrderNo("IT-AUDIT-3");
        mapper.insert(po);
        AuditRow before = auditRow("IT-AUDIT-3");

        Instant explicit = Instant.parse("2026-09-26T11:00:00Z");
        AuditProbePO patch = new AuditProbePO();
        patch.setId(po.getId());
        patch.setOrderNo("IT-AUDIT-3");
        patch.setUpdater("reviewer");
        patch.setUpdateTime(explicit);
        assertThat(mapper.updateById(patch)).isEqualTo(1);

        AuditRow after = auditRow("IT-AUDIT-3");
        assertThat(after.creator()).as("creator 是历史，永不改写").isEqualTo(before.creator());
        assertThat(after.createTime()).as("create_time 是历史，永不改写").isEqualTo(before.createTime());
        // 更新时的覆盖语义是 Task 14 设计要点 6 的决策，理由写在 AuditMetaObjectHandler#forceUpdateFill：
        // strictUpdateFill 的判空会让"从库里读出来的实体"永远不改时间戳。所以实体里显式赋的值就是会被写掉，
        // 本条用例钉的是"真 MP 确实回调了我们"——没回调的话读回来的是 reviewer 与那条 11:00。
        assertThat(after.updater()).isEqualTo(unknownPrincipal());
        assertThat(after.updateTime().toInstant()).isNotEqualTo(explicit);
    }
}
